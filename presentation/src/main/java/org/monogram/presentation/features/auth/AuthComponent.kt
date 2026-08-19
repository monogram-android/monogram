package org.monogram.presentation.features.auth

import com.arkivanov.decompose.value.Value
import org.monogram.domain.repository.AuthCodeDelivery
import org.monogram.domain.repository.AuthCodeInputKind
import org.monogram.domain.repository.AuthError
import org.monogram.domain.repository.AuthUiStatus
import org.monogram.domain.repository.TelegramBackendMode

interface AuthComponent {
    val model: Value<Model>

    fun onPhoneEntered(phone: String)
    fun onCodeEntered(code: String)
    fun onResendCode()
    fun onPasswordEntered(password: String)
    fun onSignUpSubmitted(firstName: String, lastName: String)
    fun onBackToPhone()
    fun onRetry()
    fun onProxyClicked()
    fun onTelegramBackendToggleRequested()
    fun dismissError()
    fun onReset()

    data class Model(
        val authState: AuthState,
        val uiStatus: AuthUiStatus = AuthUiStatus.Idle,
        val isSubmitting: Boolean = false,
        val error: AuthError? = null,
        val phoneNumber: String? = null,
        val telegramBackendMode: TelegramBackendMode = TelegramBackendMode.UNKNOWN,
        val isTelegramBackendSwitching: Boolean = false
    )

    sealed class AuthState {
        object InputPhone : AuthState()
        data class InputCode(
            val codeLength: Int,
            val delivery: AuthCodeDelivery,
            val inputKind: AuthCodeInputKind,
            val codeHint: String? = null,
            val nextDelivery: AuthCodeDelivery? = null,
            val timeout: Int = 0,
            val emailPattern: String? = null,
            val canResend: Boolean = false
        ) : AuthState()

        data class InputPassword(
            val passwordHint: String? = null,
            val hasRecoveryEmail: Boolean = false,
            val recoveryEmailPattern: String? = null
        ) : AuthState()

        object InputSignUp : AuthState()
    }
}
