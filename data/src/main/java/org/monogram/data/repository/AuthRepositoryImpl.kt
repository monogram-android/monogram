package org.monogram.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.drinkless.tdlib.TdApi
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
    private val scope: CoroutineScope
) : AuthRepository {
    private val _authState = MutableStateFlow<AuthStep>(AuthStep.Loading)
    override val authState = _authState.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    override val errors = _errors.asSharedFlow()

    private val initMutex = Mutex()

    init {
        scope.launch {
            // Proactively check current state in case we missed the update
            launchAuthAction {
                val state = remote.getAuthorizationState()
                handleUpdate(state)
            }

            updates.authorizationState.collect { update ->
                handleUpdate(update.authorizationState)
            }
        }
    }

    private fun handleUpdate(state: TdApi.AuthorizationState) {
        if (state is TdApi.AuthorizationStateWaitTdlibParameters) {
            sendTdLibParameters()
        }
        val domainState = state.toDomain()
        _authState.update { domainState }
    }

    private fun sendTdLibParameters() {
        if (!initMutex.tryLock()) return

        scope.launch {
            try {
                var attempts = 0
                while (true) {
                    // Double check if we still need to send parameters
                    val currentState = coRunCatching { remote.getAuthorizationState() }.getOrNull()
                    if (currentState != null && currentState !is TdApi.AuthorizationStateWaitTdlibParameters) {
                        break
                    }

                    val result = coRunCatching { remote.setTdlibParameters(parametersProvider.create()) }
                    if (result.isSuccess) {
                        // After success, immediately re-check state to move past WaitParameters
                        val nextState = coRunCatching { remote.getAuthorizationState() }.getOrNull()
                        if (nextState != null) {
                            handleUpdate(nextState)
                        }
                        break
                    }

                    val error = result.exceptionOrNull()
                    if (error?.message?.contains("Parameters are already set", ignoreCase = true) == true) {
                        break
                    }

                    attempts++
                    val delayMs = (1000L * attempts).coerceAtMost(10_000L)
                    delay(delayMs)
                }
            } finally {
                initMutex.unlock()
            }
        }
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