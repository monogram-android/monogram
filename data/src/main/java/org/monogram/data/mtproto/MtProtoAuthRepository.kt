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

/** Resets an in-flight MTProto auth session before its account state is deleted. */
internal fun interface MtProtoAuthSessionResetter {
    fun resetAuthSession()
}

/** Candidate MTProto auth adapter. It remains unselected until legacy auth lifecycle can stop safely. */
internal class MtProtoAuthRepository(
    private val sessionFactory: MtProtoAuthSessionHandleFactory,
    private val scope: CoroutineScope,
    private val accountDcStore: MtProtoAccountDcStore = NoOpMtProtoAccountDcStore,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : AuthRepository, MtProtoAuthSessionResetter {
    private data class PendingAction(
        val stage: AuthSubmissionStage,
        val payload: String? = null,
        val secondaryPayload: String? = null,
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

    override fun signUp(firstName: String, lastName: String) {
        submit(PendingAction(AuthSubmissionStage.SIGN_UP, firstName, lastName))
    }

    override fun sendLoginEmail(email: String) {
        submit(PendingAction(AuthSubmissionStage.LOGIN_EMAIL, email))
    }

    override fun retryLastAction() {
        val action = synchronized(lock) {
            if (activeJob?.isActive == true) null else pendingAction
        } ?: return
        submit(action, replaceSession = false)
    }

    override fun resetAuthSession() = reset()

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
            AuthSubmissionStage.PHONE -> requestCodeWithDcMigration(
                phone = requireNotNull(action.payload),
                handle = handle,
                actionGeneration = actionGeneration,
            )
            AuthSubmissionStage.CODE -> handle.submitCode(requireNotNull(action.payload))
            AuthSubmissionStage.RESEND -> handle.resendCode()
            AuthSubmissionStage.PASSWORD -> handle.submitPassword(requireNotNull(action.payload))
            AuthSubmissionStage.SIGN_UP -> handle.submitSignUp(
                firstName = requireNotNull(action.payload),
                lastName = requireNotNull(action.secondaryPayload),
            )
            AuthSubmissionStage.LOGIN_EMAIL -> handle.submitLoginEmail(requireNotNull(action.payload))
        }
    }

    private suspend fun requestCodeWithDcMigration(
        phone: String,
        handle: MtProtoAuthSessionHandle,
        actionGeneration: Long,
    ): AuthStep = try {
        handle.requestCode(phone)
    } catch (rpc: org.monogram.mtproto.transport.MtProtoRpcException) {
        val dcId = rpc.phoneMigrationDcId() ?: throw rpc
        val replacement = sessionFactory.open(accountSlot, dcId)
        val accepted = synchronized(lock) {
            if (generation != actionGeneration || session !== handle) {
                false
            } else {
                session = replacement
                true
            }
        }
        if (!accepted) {
            replacement.close()
            throw CancellationException("MTProto auth session was reset")
        }
        handle.close()
        val nextState = replacement.requestCode(phone)
        accountDcStore.save(accountSlot, dcId)
        nextState
    }

    private fun org.monogram.mtproto.transport.MtProtoRpcException.phoneMigrationDcId(): Int? {
        if (errorCode != PHONE_MIGRATE_ERROR_CODE) return null
        return rpcMessage.removePrefix(PHONE_MIGRATE_PREFIX)
            .takeIf { rpcMessage.startsWith(PHONE_MIGRATE_PREFIX) }
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
    }

    private fun canSubmit(stage: AuthSubmissionStage): Boolean = when (stage) {
        AuthSubmissionStage.PHONE -> _authState.value is AuthStep.InputPhone
        AuthSubmissionStage.CODE -> _authState.value is AuthStep.InputCode
        AuthSubmissionStage.RESEND -> (_authState.value as? AuthStep.InputCode)?.canResend == true
        AuthSubmissionStage.PASSWORD -> _authState.value is AuthStep.InputPassword
        AuthSubmissionStage.SIGN_UP -> _authState.value is AuthStep.InputSignUp
        AuthSubmissionStage.LOGIN_EMAIL -> _authState.value is AuthStep.InputLoginEmail
    }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
        const val PHONE_MIGRATE_ERROR_CODE = 303
        const val PHONE_MIGRATE_PREFIX = "PHONE_MIGRATE_"
    }
}
