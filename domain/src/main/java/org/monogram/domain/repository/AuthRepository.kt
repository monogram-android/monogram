package org.monogram.domain.repository

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

sealed class AuthStep {
    object Loading : AuthStep()
    object WaitParameters : AuthStep()
    object InputPhone : AuthStep()
    data class InputCode(
        val codeType: String,
        val codeLength: Int,
        val nextType: String? = null,
        val timeout: Int = 0,
        val isEmailCode: Boolean = false,
        val emailPattern: String? = null
    ) : AuthStep()
    object InputPassword : AuthStep()
    object Ready : AuthStep()
}

enum class AuthSubmissionStage {
    PHONE,
    CODE,
    PASSWORD
}

sealed class AuthUiStatus {
    object Idle : AuthUiStatus()
    data class Submitting(val stage: AuthSubmissionStage) : AuthUiStatus()
    data class SlowNetwork(val stage: AuthSubmissionStage) : AuthUiStatus()
    data class NetworkError(val stage: AuthSubmissionStage) : AuthUiStatus()
}

const val AUTH_NETWORK_TIMEOUT_ERROR = "__AUTH_NETWORK_TIMEOUT__"

interface AuthRepository {
    val authState: StateFlow<AuthStep>
    val authUiStatus: StateFlow<AuthUiStatus>
    val errors: SharedFlow<String>

    fun sendPhone(phone: String)
    fun resendCode()
    fun sendCode(code: String)
    fun sendPassword(password: String)
    fun retryLastAction()
    fun reset()
}
