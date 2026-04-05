package org.monogram.presentation.settings.dataStorage

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.monogram.domain.repository.ChatCreationRepository
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext

interface DataStorageComponent {
    val state: Value<State>
    fun onBackClicked()
    fun onAutoDownloadMobileChanged(enabled: Boolean)
    fun onAutoDownloadWifiChanged(enabled: Boolean)
    fun onAutoDownloadRoamingChanged(enabled: Boolean)
    fun onAutoDownloadFilesChanged(enabled: Boolean)
    fun onAutoDownloadStickersChanged(enabled: Boolean)
    fun onAutoDownloadVideoNotesChanged(enabled: Boolean)
    fun onAutoplayGifsChanged(enabled: Boolean)
    fun onAutoplayVideosChanged(enabled: Boolean)
    fun onEnableStreamingChanged(enabled: Boolean)
    fun onStorageUsageClicked()
    fun onNetworkUsageClicked()
    fun onClearDatabaseClicked()

    data class State(
        val autoDownloadMobile: Boolean = true,
        val autoDownloadWifi: Boolean = true,
        val autoDownloadRoaming: Boolean = false,
        val autoDownloadFiles: Boolean = false,
        val autoDownloadStickers: Boolean = true,
        val autoDownloadVideoNotes: Boolean = true,
        val autoplayGifs: Boolean = true,
        val autoplayVideos: Boolean = true,
        val enableStreaming: Boolean = true,
        val databaseSize: String = "0 B"
    )
}

class DefaultDataStorageComponent(
    context: AppComponentContext,
    private val onBack: () -> Unit,
    private val onStorageUsage: () -> Unit,
    private val onNetworkUsage: () -> Unit
) : DataStorageComponent, AppComponentContext by context {

    private val appPreferences: AppPreferences = container.preferences.appPreferences
    private val chatCreationRepository: ChatCreationRepository = container.repositories.chatCreationRepository
    private val _state = MutableValue(DataStorageComponent.State())
    override val state: Value<DataStorageComponent.State> = _state
    private val scope = componentScope

    init {
        appPreferences.autoDownloadMobile.onEach { value ->
            _state.update { it.copy(autoDownloadMobile = value) }
        }.launchIn(scope)

        appPreferences.autoDownloadWifi.onEach { value ->
            _state.update { it.copy(autoDownloadWifi = value) }
        }.launchIn(scope)

        appPreferences.autoDownloadRoaming.onEach { value ->
            _state.update { it.copy(autoDownloadRoaming = value) }
        }.launchIn(scope)

        appPreferences.autoDownloadFiles.onEach { value ->
            _state.update { it.copy(autoDownloadFiles = value) }
        }.launchIn(scope)

        appPreferences.autoDownloadStickers.onEach { value ->
            _state.update { it.copy(autoDownloadStickers = value) }
        }.launchIn(scope)

        appPreferences.autoDownloadVideoNotes.onEach { value ->
            _state.update { it.copy(autoDownloadVideoNotes = value) }
        }.launchIn(scope)

        appPreferences.autoplayGifs.onEach { value ->
            _state.update { it.copy(autoplayGifs = value) }
        }.launchIn(scope)

        appPreferences.autoplayVideos.onEach { value ->
            _state.update { it.copy(autoplayVideos = value) }
        }.launchIn(scope)

        appPreferences.enableStreaming.onEach { value ->
            _state.update { it.copy(enableStreaming = value) }
        }.launchIn(scope)

        updateDatabaseSize()
    }

    private fun updateDatabaseSize() {
        val size = chatCreationRepository.getDatabaseSize()
        _state.update { it.copy(databaseSize = formatSize(size)) }
    }

    private fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    override fun onBackClicked() {
        onBack()
    }

    override fun onAutoDownloadMobileChanged(enabled: Boolean) {
        appPreferences.setAutoDownloadMobile(enabled)
    }

    override fun onAutoDownloadWifiChanged(enabled: Boolean) {
        appPreferences.setAutoDownloadWifi(enabled)
    }

    override fun onAutoDownloadRoamingChanged(enabled: Boolean) {
        appPreferences.setAutoDownloadRoaming(enabled)
    }

    override fun onAutoDownloadFilesChanged(enabled: Boolean) {
        appPreferences.setAutoDownloadFiles(enabled)
    }

    override fun onAutoDownloadStickersChanged(enabled: Boolean) {
        appPreferences.setAutoDownloadStickers(enabled)
    }

    override fun onAutoDownloadVideoNotesChanged(enabled: Boolean) {
        appPreferences.setAutoDownloadVideoNotes(enabled)
    }

    override fun onAutoplayGifsChanged(enabled: Boolean) {
        appPreferences.setAutoplayGifs(enabled)
    }

    override fun onAutoplayVideosChanged(enabled: Boolean) {
        appPreferences.setAutoplayVideos(enabled)
    }

    override fun onEnableStreamingChanged(enabled: Boolean) {
        appPreferences.setEnableStreaming(enabled)
    }

    override fun onStorageUsageClicked() {
        onStorageUsage()
    }

    override fun onNetworkUsageClicked() {
        onNetworkUsage()
    }

    override fun onClearDatabaseClicked() {
        chatCreationRepository.clearDatabase()
        updateDatabaseSize()
    }
}
