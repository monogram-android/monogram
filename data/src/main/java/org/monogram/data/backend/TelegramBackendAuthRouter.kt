package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import org.monogram.domain.repository.AuthError
import org.monogram.domain.repository.AuthRepository
import org.monogram.domain.repository.AuthStep
import org.monogram.domain.repository.AuthUiStatus

/**
 * Exposes auth for exactly one selected backend without constructing the other backend.
 *
 * The selected backend is observed before either factory is invoked. This prevents an MTProto
 * account from starting TDLib merely because the process-wide auth contract is requested.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class TelegramBackendAuthRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> AuthRepository,
    private val mtProtoFactory: () -> AuthRepository,
    scope: CoroutineScope,
    accountId: String = DEFAULT_ACCOUNT_ID,
) : AuthRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val _authState = MutableStateFlow<AuthStep>(AuthStep.Loading)
    override val authState = _authState.asStateFlow()
    private val _authUiStatus = MutableStateFlow<AuthUiStatus>(AuthUiStatus.Idle)
    override val authUiStatus = _authUiStatus.asStateFlow()
    private val _errors = MutableSharedFlow<AuthError>(extraBufferCapacity = 1)
    override val errors = _errors.asSharedFlow()

    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)
    private val mtProto by lazy(LazyThreadSafetyMode.NONE, mtProtoFactory)

    init {
        scope.launch {
            selectionStore.observe(accountId)
                .distinctUntilChanged()
                .collect { selectedBackend.value = it }
        }
        scope.launch {
            selectedBackend.filterNotNull()
                .flatMapLatest { repositoryFor(it).authState }
                .collect { _authState.value = it }
        }
        scope.launch {
            selectedBackend.filterNotNull()
                .flatMapLatest { repositoryFor(it).authUiStatus }
                .collect { _authUiStatus.value = it }
        }
        scope.launch {
            selectedBackend.filterNotNull()
                .flatMapLatest { repositoryFor(it).errors }
                .collect { _errors.emit(it) }
        }
    }

    override fun sendPhone(phone: String) {
        selectedRepository()?.sendPhone(phone)
    }

    override fun resendCode() {
        selectedRepository()?.resendCode()
    }

    override fun sendCode(code: String) {
        selectedRepository()?.sendCode(code)
    }

    override fun sendPassword(password: String) {
        selectedRepository()?.sendPassword(password)
    }

    override fun signUp(firstName: String, lastName: String) {
        selectedRepository()?.signUp(firstName, lastName)
    }

    override fun retryLastAction() {
        selectedRepository()?.retryLastAction()
    }

    override fun reset() {
        selectedRepository()?.reset()
    }

    private fun selectedRepository(): AuthRepository? = selectedBackend.value?.let(::repositoryFor)

    private fun repositoryFor(backend: TelegramBackendKind): AuthRepository = when (backend) {
        TelegramBackendKind.LEGACY -> legacy
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto
    }

    private companion object {
        const val DEFAULT_ACCOUNT_ID = "default"
    }
}
