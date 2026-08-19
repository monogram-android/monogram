package org.monogram.presentation.features.auth

import android.util.Log
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.monogram.domain.repository.AuthError
import org.monogram.domain.repository.AuthRepository
import org.monogram.domain.repository.AuthStep
import org.monogram.domain.repository.AuthUiStatus
import org.monogram.domain.repository.TelegramBackendMode
import org.monogram.domain.repository.TelegramBackendModeRepository
import org.monogram.domain.repository.TelegramBackendSwitchRepository
import org.monogram.presentation.BuildConfig
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext

class DefaultAuthComponent(
    context: AppComponentContext,
    private val onOpenProxy: () -> Unit
) : AuthComponent, AppComponentContext by context {

    private val repository: AuthRepository = container.repositories.authRepository
    private val backendModeRepository: TelegramBackendModeRepository =
        container.repositories.telegramBackendModeRepository
    private val backendSwitchRepository: TelegramBackendSwitchRepository =
        container.repositories.telegramBackendSwitchRepository
    private val scope = componentScope

    private val _model = MutableValue(
        AuthComponent.Model(authState = AuthComponent.AuthState.InputPhone)
    )
    override val model: Value<AuthComponent.Model> = _model

    init {
        backendModeRepository.backendMode
            .onEach { backendMode ->
                _model.update {
                    it.copy(telegramBackendMode = backendMode, isTelegramBackendSwitching = false)
                }
            }
            .launchIn(scope)

        repository.authState
            .onEach { step ->
                val newAuthState = when (step) {
                    is AuthStep.InputPhone -> AuthComponent.AuthState.InputPhone
                    is AuthStep.InputCode -> AuthComponent.AuthState.InputCode(
                        codeLength = step.codeLength,
                        delivery = step.delivery,
                        inputKind = step.inputKind,
                        codeHint = step.codeHint,
                        nextDelivery = step.nextDelivery,
                        timeout = step.timeout,
                        emailPattern = step.emailPattern,
                        canResend = step.canResend
                    )

                    is AuthStep.InputPassword -> AuthComponent.AuthState.InputPassword(
                        passwordHint = step.passwordHint,
                        hasRecoveryEmail = step.hasRecoveryEmail,
                        recoveryEmailPattern = step.recoveryEmailPattern
                    )
                    AuthStep.InputSignUp -> AuthComponent.AuthState.InputSignUp
                    else -> null
                }
                if (newAuthState != null) {
                    _model.update {
                        it.copy(
                            authState = newAuthState,
                            isSubmitting = repository.authUiStatus.value.isSubmitting(),
                            uiStatus = repository.authUiStatus.value
                        )
                    }
                }
            }
            .launchIn(scope)

        repository.authUiStatus
            .onEach { status ->
                _model.update {
                    it.copy(
                        uiStatus = status,
                        isSubmitting = status.isSubmitting()
                    )
                }
            }
            .launchIn(scope)

        repository.errors
            .onEach { errorMessage ->
                _model.update {
                    it.copy(
                        error = errorMessage,
                        isSubmitting = false
                    )
                }
            }
            .launchIn(scope)
    }

    override fun onPhoneEntered(phone: String) {
        _model.update { it.copy(isSubmitting = true, phoneNumber = phone) }
        repository.sendPhone(phone)
    }

    override fun onCodeEntered(code: String) {
        _model.update { it.copy(isSubmitting = true) }
        repository.sendCode(code)
    }

    override fun onResendCode() {
        repository.resendCode()
    }

    override fun onPasswordEntered(password: String) {
        _model.update { it.copy(isSubmitting = true) }
        repository.sendPassword(password)
    }

    override fun onSignUpSubmitted(firstName: String, lastName: String) {
        _model.update { it.copy(isSubmitting = true) }
        repository.signUp(firstName, lastName)
    }

    override fun onBackToPhone() {
        _model.update { it.copy(error = null) }
        repository.reset()
    }

    override fun onRetry() {
        _model.update { it.copy(error = null) }
        repository.retryLastAction()
    }

    override fun onProxyClicked() {
        onOpenProxy()
    }

    override fun onTelegramBackendToggleRequested() {
        if (!BuildConfig.DEBUG || _model.value.isTelegramBackendSwitching) return
        val target = when (_model.value.telegramBackendMode) {
            TelegramBackendMode.LEGACY -> TelegramBackendMode.KOTLIN_MTPROTO
            TelegramBackendMode.KOTLIN_MTPROTO -> TelegramBackendMode.LEGACY
            TelegramBackendMode.UNKNOWN -> return
        }
        scope.launch {
            _model.update { it.copy(isTelegramBackendSwitching = true) }
            runCatching { backendSwitchRepository.switchTo(target) }
                .onFailure { error ->
                    Log.e(TAG, "Unable to switch Telegram backend", error)
                    _model.update {
                        it.copy(
                            error = AuthError.Unexpected,
                            isTelegramBackendSwitching = false,
                        )
                    }
                }
        }
    }

    override fun dismissError() {
        _model.update { it.copy(error = null) }
    }

    override fun onReset() {
        _model.update { it.copy(error = null) }
        repository.reset()
    }
}

private const val TAG = "DefaultAuthComponent"

private fun AuthUiStatus.isSubmitting(): Boolean {
    return this is AuthUiStatus.Submitting || this is AuthUiStatus.SlowNetwork
}
