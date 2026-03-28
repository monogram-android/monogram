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

interface AuthRepository {
    val authState: StateFlow<AuthStep>
    val errors: SharedFlow<String>

    fun sendPhone(phone: String)
    fun resendCode()
    fun sendCode(code: String)
    fun sendPassword(password: String)
    fun reset()
}
