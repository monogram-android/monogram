package org.monogram.data.repository

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import org.monogram.core.ScopeProvider
import org.monogram.data.core.coRunCatching
import org.monogram.data.datasource.remote.AuthRemoteDataSource
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.data.gateway.toUserMessage
import org.monogram.data.infra.TdLibParametersProvider
import org.monogram.data.mapper.toDomain
import org.monogram.domain.repository.AuthRepository
import org.monogram.domain.repository.AuthStep

class AuthRepositoryImpl(
    private val parametersProvider: TdLibParametersProvider,
    private val remote: AuthRemoteDataSource,
    private val updates: UpdateDispatcher,
    scopeProvider: ScopeProvider
) : AuthRepository {
    private val scope = scopeProvider.appScope

    private val _authState = MutableStateFlow<AuthStep>(AuthStep.Loading)
    override val authState = _authState.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    override val errors = _errors.asSharedFlow()

    init {
        scope.launch {
            updates.authorizationState.collect { update ->
                if (update.authorizationState is TdApi.AuthorizationStateWaitTdlibParameters) {
                    sendTdLibParameters()
                }
                val domainState = update.authorizationState.toDomain()
                _authState.update { domainState }
            }
        }
    }

    private suspend fun sendTdLibParameters() {
        coRunCatching { remote.setTdlibParameters(parametersProvider.create()) }
            .onFailure { emitError(it) }
    }

    private fun launchAuthAction(action: suspend () -> Unit) {
        scope.launch {
            coRunCatching { action() }
                .onFailure { emitError(it) }
        }
    }

    override fun sendPhone(phone: String) {
        launchAuthAction { remote.setPhoneNumber(phone) }
    }

    override fun resendCode() {
        launchAuthAction { remote.resendCode() }
    }

    override fun sendCode(code: String) {
        launchAuthAction {
            val isEmail = (_authState.value as? AuthStep.InputCode)?.isEmailCode == true
            if (isEmail) remote.checkEmailCode(code) else remote.setAuthCode(code)
        }
    }

    override fun sendPassword(password: String) {
        launchAuthAction { remote.checkPassword(password) }
    }

    override fun reset() {
        _authState.update { AuthStep.InputPhone }
    }

    private fun emitError(t: Throwable) {
        _errors.tryEmit(t.toUserMessage())
    }
}