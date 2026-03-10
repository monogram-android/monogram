package org.monogram.presentation.settings.networkUsage

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.launch
import org.monogram.domain.models.NetworkUsageModel
import org.monogram.domain.repository.SettingsRepository
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext

interface NetworkUsageComponent {
    val state: Value<State>
    fun onBackClicked()
    fun onResetClicked()
    fun onToggleNetworkStats(enabled: Boolean)

    data class State(
        val usage: NetworkUsageModel? = null,
        val isLoading: Boolean = true,
        val isNetworkStatsEnabled: Boolean = true
    )
}

class DefaultNetworkUsageComponent(
    context: AppComponentContext,
    private val onBack: () -> Unit
) : NetworkUsageComponent, AppComponentContext by context {

    private val settingsRepository: SettingsRepository = container.repositories.settingsRepository
    private val _state = MutableValue(NetworkUsageComponent.State())
    override val state: Value<NetworkUsageComponent.State> = _state
    private val scope = componentScope

    init {
        loadStatistics()
    }

    private fun loadStatistics() {
        _state.update { it.copy(isLoading = true) }
        scope.launch {
            val isEnabled = settingsRepository.getNetworkStatisticsEnabled()
            val usage = if (isEnabled) settingsRepository.getNetworkUsage() else null
            _state.update { it.copy(usage = usage, isLoading = false, isNetworkStatsEnabled = isEnabled) }
        }
    }

    override fun onBackClicked() {
        onBack()
    }

    override fun onResetClicked() {
        scope.launch {
            val success = settingsRepository.resetNetworkStatistics()
            if (success) {
                loadStatistics()
            }
        }
    }

    override fun onToggleNetworkStats(enabled: Boolean) {
        scope.launch {
            settingsRepository.setNetworkStatisticsEnabled(enabled)
            if (!enabled) {
                settingsRepository.resetNetworkStatistics()
            }
            _state.update { it.copy(isNetworkStatsEnabled = enabled) }
            if (enabled) {
                loadStatistics()
            } else {
                _state.update { it.copy(usage = null) }
            }
        }
    }
}
