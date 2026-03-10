package org.monogram.presentation.settings.powersaving

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext

interface PowerSavingComponent {
    val state: Value<State>

    fun onBackClicked()
    fun onChatAnimationsToggled(enabled: Boolean)
    fun onBackgroundServiceToggled(enabled: Boolean)
    fun onPowerSavingModeToggled(enabled: Boolean)
    fun onWakeLockToggled(enabled: Boolean)
    fun onBatteryOptimizationToggled(enabled: Boolean)

    data class State(
        val isChatAnimationsEnabled: Boolean = true,
        val backgroundServiceEnabled: Boolean = true,
        val isPowerSavingModeEnabled: Boolean = false,
        val isWakeLockEnabled: Boolean = true,
        val batteryOptimizationEnabled: Boolean = false
    )
}

class DefaultPowerSavingComponent(
    context: AppComponentContext,
    private val onBack: () -> Unit
) : PowerSavingComponent, AppComponentContext by context {

    private val appPreferences: AppPreferencesProvider = container.preferences.appPreferences
    private val _state = MutableValue(PowerSavingComponent.State())
    override val state: Value<PowerSavingComponent.State> = _state
    private val scope = componentScope

    init {
        appPreferences.isChatAnimationsEnabled.onEach { value ->
            _state.update { it.copy(isChatAnimationsEnabled = value) }
        }.launchIn(scope)

        appPreferences.backgroundServiceEnabled.onEach { value ->
            _state.update { it.copy(backgroundServiceEnabled = value) }
        }.launchIn(scope)

        appPreferences.isPowerSavingMode.onEach { value ->
            _state.update { it.copy(isPowerSavingModeEnabled = value) }
        }.launchIn(scope)

        appPreferences.isWakeLockEnabled.onEach { value ->
            _state.update { it.copy(isWakeLockEnabled = value) }
        }.launchIn(scope)

        appPreferences.batteryOptimizationEnabled.onEach { value ->
            _state.update { it.copy(batteryOptimizationEnabled = value) }
        }.launchIn(scope)
    }

    override fun onBackClicked() {
        onBack()
    }

    override fun onChatAnimationsToggled(enabled: Boolean) {
        appPreferences.setChatAnimationsEnabled(enabled)
    }

    override fun onBackgroundServiceToggled(enabled: Boolean) {
        appPreferences.setBackgroundServiceEnabled(enabled)
    }

    override fun onPowerSavingModeToggled(enabled: Boolean) {
        appPreferences.setPowerSavingMode(enabled)
    }

    override fun onWakeLockToggled(enabled: Boolean) {
        appPreferences.setWakeLockEnabled(enabled)
    }

    override fun onBatteryOptimizationToggled(enabled: Boolean) {
        appPreferences.setBatteryOptimizationEnabled(enabled)
    }
}
