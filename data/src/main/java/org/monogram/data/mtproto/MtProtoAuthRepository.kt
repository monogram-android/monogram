package org.monogram.data.mtproto

import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.monogram.data.gateway.toAuthError
import org.monogram.domain.repository.AuthError
import org.monogram.domain.repository.AuthRepository
import org.monogram.domain.repository.AuthStep
import org.monogram.domain.repository.AuthSubmissionStage
import org.monogram.domain.repository.AuthUiStatus

/** Candidate MTProto auth adapter. It is intentionally not selected by DI yet. */
internal class MtProtoAuthRepository(
    private val sessionFactory: MtProtoAuthSessionHandleFactory,
    private val scope: CoroutineScope,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : AuthRepository {
    private data class PendingAction(
        val stage: AuthSubmissionStage,
        val payload: String? = null,
    )

    private val lock = Any()
    private val _authState = MutableStateFlow<AuthStep>(AuthStep.InputPhone)
    override val authState = _authState.asStateFlow()
    private val _authUiStatus = MutableStateFlow<AuthUiStatus>(AuthUiStatus.Idle)
    override val authUiStatus = _authUiStatus.asStateFlow()
    private val _errors = MutableSharedFlow<AuthError>(extraBufferCapacity = 1)
    override val errors = _errors.asSharedFlow()

    private var session: MtProtoAuthSessionHandle? = null
    private var activeJob: Job? = null
    private var pendingAction: PendingAction? = null
    private var generation = 0L

    override fun sendPhone(phone: String) {
        submit(PendingAction(AuthSubmissionStage.PHONE, phone), replaceSession = true)
    }

    override fun resendCode() {
        submit(PendingAction(AuthSubmissionStage.RESEND))
    }

    override fun sendCode(code: String) {
        submit(PendingAction(AuthSubmissionStage.CODE, code))
    }

    override fun sendPassword(password: String) {
        submit(PendingAction(AuthSubmissionStage.PASSWORD, password))
    }

    override fun retryLastAction() {
        val action = synchronized(lock) {
            if (activeJob?.isActive == true) null else pendingAction
        } ?: return
        submit(action, replaceSession = false)
    }

    override fun reset() {
        val handleToClose: MtProtoAuthSessionHandle?
        synchronized(lock) {
            generation++
            activeJob?.cancel()
            activeJob = null
            pendingAction = null
            handleToClose = session
            session = null
            _authState.value = AuthStep.InputPhone
            _authUiStatus.value = AuthUiStatus.Idle
        }
        handleToClose?.close()
    }

    private fun submit(action: PendingAction, replaceSession: Boolean = false) {
        synchronized(lock) {
            if (activeJob?.isActive == true || !canSubmit(action.stage)) return

            pendingAction = action
            _authUiStatus.value = AuthUiStatus.Submitting(action.stage)
            val actionGeneration = generation
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val nextState = execute(action, replaceSession, actionGeneration)
                    currentCoroutineContext().ensureActive()
                    var completedSession: MtProtoAuthSessionHandle? = null
                    synchronized(lock) {
                        if (generation != actionGeneration) return@synchronized
                        _authState.value = nextState
                        pendingAction = null
                        if (nextState is AuthStep.Ready) {
                            completedSession = session
                            session = null
                        }
                    }
                    completedSession?.close()
                } catch (cancelled: CancellationException) {
                    val cancelledSession = synchronized(lock) {
                        if (generation == actionGeneration) session.also { session = null } else null
                    }
                    cancelledSession?.close()
                    throw cancelled
                } catch (failure: Throwable) {
                    synchronized(lock) {
                        if (generation == actionGeneration) {
                            _errors.tryEmit(failure.toAuthError())
                        }
                    }
                } finally {
                    synchronized(lock) {
                        if (generation == actionGeneration) {
                            activeJob = null
                            _authUiStatus.value = AuthUiStatus.Idle
                        }
                    }
                }
            }
            activeJob = job
            job.start()
        }
    }

    private suspend fun execute(
        action: PendingAction,
        replaceSession: Boolean,
        actionGeneration: Long,
    ): AuthStep {
        val currentSession = synchronized(lock) { session }
        val handle = if (action.stage == AuthSubmissionStage.PHONE && (replaceSession || currentSession == null)) {
            val opened = sessionFactory.open(accountSlot)
            var accepted = false
            val previous = synchronized(lock) {
                if (generation != actionGeneration) {
                    null
                } else {
                    accepted = true
                    val old = session
                    session = opened
                    old
                }
            }
            if (!accepted) {
                opened.close()
                throw CancellationException("MTProto auth session was reset")
            }
            previous?.close()
            currentCoroutineContext().ensureActive()
            opened
        } else {
            currentSession ?: error("MTProto auth session is not open")
        }

        return when (action.stage) {
            AuthSubmissionStage.PHONE -> handle.requestCode(requireNotNull(action.payload))
            AuthSubmissionStage.CODE -> handle.submitCode(requireNotNull(action.payload))
            AuthSubmissionStage.RESEND -> handle.resendCode()
            AuthSubmissionStage.PASSWORD -> handle.submitPassword(requireNotNull(action.payload))
        }
    }

    private fun canSubmit(stage: AuthSubmissionStage): Boolean = when (stage) {
        AuthSubmissionStage.PHONE -> _authState.value is AuthStep.InputPhone
        AuthSubmissionStage.CODE -> _authState.value is AuthStep.InputCode
        AuthSubmissionStage.RESEND -> (_authState.value as? AuthStep.InputCode)?.canResend == true
        AuthSubmissionStage.PASSWORD -> _authState.value is AuthStep.InputPassword
    }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
    }
}
