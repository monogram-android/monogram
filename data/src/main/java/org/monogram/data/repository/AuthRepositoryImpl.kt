package org.monogram.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
import org.monogram.domain.repository.AUTH_NETWORK_TIMEOUT_ERROR
import org.monogram.domain.repository.AuthRepository
import org.monogram.domain.repository.AuthSubmissionStage
import org.monogram.domain.repository.AuthStep
import org.monogram.domain.repository.AuthUiStatus

class AuthRepositoryImpl(
    private val parametersProvider: TdLibParametersProvider,
    private val remote: AuthRemoteDataSource,
    private val updates: UpdateDispatcher,
    private val scope: CoroutineScope
) : AuthRepository {
    private data class PendingAuthAction(
        val stage: AuthSubmissionStage,
        val payload: String
    )

    private companion object {
        const val SLOW_NETWORK_TIMEOUT_MS = 12_000L
        const val NETWORK_ERROR_TIMEOUT_MS = 30_000L
    }

    private val _authState = MutableStateFlow<AuthStep>(AuthStep.Loading)
    override val authState = _authState.asStateFlow()

    private val _authUiStatus = MutableStateFlow<AuthUiStatus>(AuthUiStatus.Idle)
    override val authUiStatus = _authUiStatus.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    override val errors = _errors.asSharedFlow()

    private val initMutex = Mutex()
    private var activeWatchdog: Job? = null
    private var activeWatchdogId = 0L
    private var pendingAction: PendingAuthAction? = null

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
        if (pendingAction != null && isExpectedNextState(pendingAction!!.stage, domainState)) {
            clearPendingAuthState()
            pendingAction = null
        }
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
        submitAuthAction(AuthSubmissionStage.PHONE, phone) {
            remote.setPhoneNumber(phone)
        }
    }

    override fun resendCode() {
        launchAuthAction { remote.resendCode() }
    }

    override fun sendCode(code: String) {
        submitAuthAction(AuthSubmissionStage.CODE, code) {
            val isEmail = (_authState.value as? AuthStep.InputCode)?.isEmailCode == true
            if (isEmail) remote.checkEmailCode(code) else remote.setAuthCode(code)
        }
    }

    override fun sendPassword(password: String) {
        submitAuthAction(AuthSubmissionStage.PASSWORD, password) {
            remote.checkPassword(password)
        }
    }

    override fun retryLastAction() {
        when (val action = pendingAction) {
            null -> Unit
            else -> when (action.stage) {
                AuthSubmissionStage.PHONE -> sendPhone(action.payload)
                AuthSubmissionStage.CODE -> sendCode(action.payload)
                AuthSubmissionStage.PASSWORD -> sendPassword(action.payload)
            }
        }
    }

    override fun reset() {
        clearPendingAuthState()
        pendingAction = null
        _authState.update { AuthStep.InputPhone }
    }

    private fun emitError(t: Throwable) {
        clearPendingAuthState()
        _errors.tryEmit(t.toUserMessage())
    }

    private fun submitAuthAction(
        stage: AuthSubmissionStage,
        payload: String,
        action: suspend () -> Unit
    ) {
        pendingAction = PendingAuthAction(stage, payload)
        clearPendingAuthState()
        _authUiStatus.value = AuthUiStatus.Submitting(stage)

        scope.launch {
            coRunCatching { action() }
                .onSuccess { startAuthWatchdog(stage) }
                .onFailure { emitError(it) }
        }
    }

    private fun startAuthWatchdog(stage: AuthSubmissionStage) {
        val watchdogId = ++activeWatchdogId
        activeWatchdog?.cancel()
        activeWatchdog = scope.launch {
            delay(SLOW_NETWORK_TIMEOUT_MS)
            if (!isWatchdogStillActive(watchdogId, stage)) return@launch
            _authUiStatus.value = AuthUiStatus.SlowNetwork(stage)

            delay(NETWORK_ERROR_TIMEOUT_MS - SLOW_NETWORK_TIMEOUT_MS)
            if (!isWatchdogStillActive(watchdogId, stage)) return@launch
            _authUiStatus.value = AuthUiStatus.NetworkError(stage)
            _errors.tryEmit(AUTH_NETWORK_TIMEOUT_ERROR)
        }
    }

    private fun isWatchdogStillActive(
        watchdogId: Long,
        stage: AuthSubmissionStage
    ): Boolean {
        val currentPendingAction = pendingAction ?: return false
        return activeWatchdogId == watchdogId &&
                currentPendingAction.stage == stage &&
                !isExpectedNextState(stage, _authState.value)
    }

    private fun clearPendingAuthState() {
        activeWatchdog?.cancel()
        activeWatchdog = null
        _authUiStatus.value = AuthUiStatus.Idle
    }

    private fun isExpectedNextState(
        stage: AuthSubmissionStage,
        state: AuthStep
    ): Boolean {
        return when (stage) {
            AuthSubmissionStage.PHONE -> state !is AuthStep.InputPhone &&
                    state !is AuthStep.Loading &&
                    state !is AuthStep.WaitParameters
            AuthSubmissionStage.CODE -> state is AuthStep.InputPassword ||
                    state is AuthStep.Ready
            AuthSubmissionStage.PASSWORD -> state is AuthStep.Ready
        }
    }
}
