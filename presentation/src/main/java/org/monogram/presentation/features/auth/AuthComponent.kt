package org.monogram.presentation.features.auth

import com.arkivanov.decompose.value.Value

interface AuthComponent {
    val model: Value<Model>

    fun onPhoneEntered(phone: String)
    fun onCodeEntered(code: String)
    fun onResendCode()
    fun onPasswordEntered(password: String)
    fun onBackToPhone()
    fun onProxyClicked()
    fun dismissError()
    fun onReset()

    data class Model(
        val authState: AuthState,
        val isSubmitting: Boolean = false,
        val error: String? = null,
        val phoneNumber: String? = null
    )

    sealed class AuthState {
        object InputPhone : AuthState()
        data class InputCode(
            val codeLength: Int,
            val codeType: String,
            val nextCodeType: String? = null,
            val timeout: Int = 0,
            val emailPattern: String? = null
        ) : AuthState()

        object InputPassword : AuthState()
    }
}
