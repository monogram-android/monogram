package org.monogram.presentation.settings.storage

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import coil3.annotation.ExperimentalCoilApi
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.monogram.domain.models.StorageUsageModel
import org.monogram.domain.repository.StickerRepository
import org.monogram.domain.repository.StorageRepository
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext

interface StorageUsageComponent {
    val state: Value<State>
    fun onBackClicked()
    fun onClearAllClicked()
    fun onClearChatClicked(chatId: Long)
    fun onCacheLimitSizeChanged(size: Long)
    fun onAutoClearCacheTimeChanged(time: Int)
    fun onStorageOptimizerChanged(enabled: Boolean)

    data class State(
        val usage: StorageUsageModel? = null,
        val isLoading: Boolean = true,
        val cacheLimitSize: Long = -1L,
        val autoClearCacheTime: Int = -1,
        val isStorageOptimizerEnabled: Boolean = false
    )
}

class DefaultStorageUsageComponent(
    context: AppComponentContext,
    private val onBack: () -> Unit
) : StorageUsageComponent, AppComponentContext by context {

    private val storageRepository: StorageRepository = container.repositories.storageRepository
    private val appPreferences: AppPreferences = container.preferences.appPreferences
    private val stickerRepository: StickerRepository = container.repositories.stickerRepository
    private val cacheController: CacheController = container.utils.cacheController

    private val _state = MutableValue(StorageUsageComponent.State())
    override val state: Value<StorageUsageComponent.State> = _state
    private val scope = componentScope

    init {
        loadStatistics()
        appPreferences.cacheLimitSize.onEach { value ->
            _state.update { it.copy(cacheLimitSize = value) }
        }.launchIn(scope)

        appPreferences.autoClearCacheTime.onEach { value ->
            _state.update { it.copy(autoClearCacheTime = value) }
        }.launchIn(scope)

        scope.launch {
            val enabled = storageRepository.getStorageOptimizerEnabled()
            _state.update { it.copy(isStorageOptimizerEnabled = enabled) }
        }
    }

    private fun loadStatistics() {
        _state.update { it.copy(isLoading = true) }
        scope.launch {
            val usage = storageRepository.getStorageUsage()
            _state.update { it.copy(usage = usage, isLoading = false) }
        }
    }

    private fun updateDatabaseMaintenanceSettings(
        limit: Long = _state.value.cacheLimitSize,
        time: Int = _state.value.autoClearCacheTime
    ) {
        scope.launch {
            val ttl = if (time > 0) time * 24 * 60 * 60 else -1
            storageRepository.setDatabaseMaintenanceSettings(limit, ttl)
            loadStatistics()
        }
    }

    override fun onBackClicked() {
        onBack()
    }

    @OptIn(UnstableApi::class, ExperimentalCoilApi::class)
    override fun onClearAllClicked() {
        scope.launch {
            val success = storageRepository.clearStorage()
            if (success) {
                stickerRepository.clearCache()
                cacheController.clearAllCache()
                loadStatistics()
            }
        }
    }

    override fun onClearChatClicked(chatId: Long) {
        scope.launch {
            val success = storageRepository.clearStorage(chatId)
            if (success) {
                loadStatistics()
            }
        }
    }

    override fun onCacheLimitSizeChanged(size: Long) {
        appPreferences.setCacheLimitSize(size)
        updateDatabaseMaintenanceSettings(limit = size)
    }

    override fun onAutoClearCacheTimeChanged(time: Int) {
        appPreferences.setAutoClearCacheTime(time)
        updateDatabaseMaintenanceSettings(time = time)
    }

    override fun onStorageOptimizerChanged(enabled: Boolean) {
        scope.launch {
            storageRepository.setStorageOptimizerEnabled(enabled)
            _state.update { it.copy(isStorageOptimizerEnabled = enabled) }
        }
    }
}
