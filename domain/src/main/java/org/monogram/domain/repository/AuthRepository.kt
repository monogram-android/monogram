package org.monogram.domain.repository

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

sealed class AuthStep {
    object Loading : AuthStep()
    object WaitParameters : AuthStep()
    object Closing : AuthStep()
    object InputPhone : AuthStep()
    data class InputCode(
        val delivery: AuthCodeDelivery,
        val codeLength: Int,
        val inputKind: AuthCodeInputKind = AuthCodeInputKind.NUMERIC,
        val codeHint: String? = null,
        val nextDelivery: AuthCodeDelivery? = null,
        val timeout: Int = 0,
        val isEmailCode: Boolean = false,
        val emailPattern: String? = null,
        val canResend: Boolean = false
    ) : AuthStep()

    data class InputPassword(
        val passwordHint: String? = null,
        val hasRecoveryEmail: Boolean = false,
        val recoveryEmailPattern: String? = null
    ) : AuthStep()
    object Ready : AuthStep()
}

enum class AuthCodeDelivery {
    TELEGRAM_MESSAGE,
    SMS,
    SMS_WORD,
    SMS_PHRASE,
    CALL,
    FLASH_CALL,
    MISSED_CALL,
    FRAGMENT,
    FIREBASE_ANDROID,
    FIREBASE_IOS,
    EMAIL,
    UNKNOWN
}

enum class AuthCodeInputKind {
    NUMERIC,
    TEXT
}

enum class AuthSubmissionStage {
    PHONE,
    CODE,
    RESEND,
    PASSWORD
}

sealed class AuthUiStatus {
    object Idle : AuthUiStatus()
    data class Submitting(val stage: AuthSubmissionStage) : AuthUiStatus()
    data class SlowNetwork(val stage: AuthSubmissionStage) : AuthUiStatus()
    data class NetworkError(val stage: AuthSubmissionStage) : AuthUiStatus()
}

sealed class AuthError {
    object InvalidCode : AuthError()
    object InvalidPassword : AuthError()
    object CodeExpired : AuthError()
    object SignUpRequired : AuthError()
    data class RateLimited(val retryAfterSeconds: Int?) : AuthError()
    object NetworkTimeout : AuthError()
    object Unexpected : AuthError()
}

const val AUTH_NETWORK_TIMEOUT_ERROR = "__AUTH_NETWORK_TIMEOUT__"

interface AuthRepository {
    val authState: StateFlow<AuthStep>
    val authUiStatus: StateFlow<AuthUiStatus>
    val errors: SharedFlow<AuthError>

    fun sendPhone(phone: String)
    fun resendCode()
    fun sendCode(code: String)
    fun sendPassword(password: String)
    fun retryLastAction()
    fun reset()
}
