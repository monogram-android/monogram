package org.monogram.presentation.settings.privacy

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.presentation.root.AppComponentContext

interface PasscodeComponent {
    val state: Value<State>
    fun onBackClicked()
    fun onPasscodeEntered(passcode: String)
    fun onClearPasscode()

    data class State(
        val isPasscodeSet: Boolean = false,
        val error: String? = null
    )
}

class DefaultPasscodeComponent(
    context: AppComponentContext,
    private val onBack: () -> Unit
) : PasscodeComponent, AppComponentContext by context {

    private val appPreferences: AppPreferencesProvider = container.preferences.appPreferences
    private val _state = MutableValue(PasscodeComponent.State(isPasscodeSet = appPreferences.passcode.value != null))
    override val state: Value<PasscodeComponent.State> = _state

    override fun onBackClicked() {
        onBack()
    }

    override fun onPasscodeEntered(passcode: String) {
        if (passcode.length < 4) {
            _state.update { it.copy(error = "Passcode must be at least 4 digits") }
            return
        }
        appPreferences.setPasscode(passcode)
        _state.update { it.copy(isPasscodeSet = true, error = null) }
        onBack()
    }

    override fun onClearPasscode() {
        appPreferences.setPasscode(null)
        appPreferences.setBiometricEnabled(false)
        _state.update { it.copy(isPasscodeSet = false) }
        onBack()
    }
}
