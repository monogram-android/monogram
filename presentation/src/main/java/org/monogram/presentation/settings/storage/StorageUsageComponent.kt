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
import org.monogram.domain.models.ChatStorageUsageModel
import org.monogram.domain.models.FileTypeStorageUsageModel
import org.monogram.domain.models.StorageCleanupResultModel
import org.monogram.domain.models.StorageUsageBreakdownModel
import org.monogram.domain.models.StorageUsageModel
import org.monogram.domain.repository.MessageDisplayer
import org.monogram.domain.repository.StickerRepository
import org.monogram.domain.repository.StorageRepository
import org.monogram.domain.repository.StringProvider
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext
import java.util.Locale

internal const val AppTempChatId: Long = Long.MIN_VALUE
private const val AppTempFileType = "AppTemp"

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
        val breakdown: StorageUsageBreakdownModel? = null,
        val appTempUsage: AppTempCacheUsage = AppTempCacheUsage(0L, 0),
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
    private val stringProvider: StringProvider = container.utils.stringProvider()
    private val messageDisplayer: MessageDisplayer = container.utils.messageDisplayer()

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
            val appTempUsage = cacheController.getAppTempUsage()
            val usage = mergeStorageUsageWithAppTemp(
                storageUsage = storageRepository.getStorageUsage(),
                appTempUsage = appTempUsage,
                appTempTitle = stringProvider.getString("storage_other_cache")
            )
            val breakdown = storageRepository.getStorageUsageBreakdown()
            _state.update {
                it.copy(
                    usage = usage,
                    breakdown = breakdown,
                    appTempUsage = appTempUsage,
                    isLoading = false
                )
            }
        }
    }

    override fun onBackClicked() {
        onBack()
    }

    @OptIn(UnstableApi::class, ExperimentalCoilApi::class)
    override fun onClearAllClicked() {
        scope.launch {
            val appTempUsage = cacheController.getAppTempUsage()
            val cleanupResult = storageRepository.clearStorage()
            stickerRepository.clearCache()
            cacheController.clearAllCache()
            showCleanupMessage(cleanupResult, appTempUsage.size)
            loadStatistics()
        }
    }

    override fun onClearChatClicked(chatId: Long) {
        if (chatId == AppTempChatId) {
            scope.launch {
                val appTempUsage = cacheController.getAppTempUsage()
                stickerRepository.clearCache()
                cacheController.clearAllCache()
                showCleanupMessage(
                    cleanupResult = StorageCleanupResultModel(
                        tdlibFreedSize = 0L,
                        tdlibFreedFileCount = 0,
                        tdlibCleanupSucceeded = true
                    ),
                    appTempFreedSize = appTempUsage.size
                )
                loadStatistics()
            }
            return
        }
        scope.launch {
            val cleanupResult = storageRepository.clearStorage(chatId)
            showCleanupMessage(cleanupResult)
            loadStatistics()
        }
    }

    override fun onCacheLimitSizeChanged(size: Long) {
        appPreferences.setCacheLimitSize(size)
    }

    override fun onAutoClearCacheTimeChanged(time: Int) {
        appPreferences.setAutoClearCacheTime(time)
    }

    override fun onStorageOptimizerChanged(enabled: Boolean) {
        scope.launch {
            storageRepository.setStorageOptimizerEnabled(enabled)
            _state.update { it.copy(isStorageOptimizerEnabled = enabled) }
        }
    }

    private fun showCleanupMessage(
        cleanupResult: StorageCleanupResultModel,
        appTempFreedSize: Long = 0L
    ) {
        val totalFreedSize = cleanupResult.tdlibFreedSize + appTempFreedSize
        val message = when {
            totalFreedSize > 0L && cleanupResult.tdlibCleanupSucceeded -> {
                stringProvider.getString(
                    "storage_cleanup_result_freed",
                    formatStorageSize(totalFreedSize)
                )
            }

            totalFreedSize > 0L -> {
                stringProvider.getString(
                    "storage_cleanup_result_partial",
                    formatStorageSize(totalFreedSize)
                )
            }

            cleanupResult.tdlibCleanupSucceeded -> {
                stringProvider.getString("storage_cleanup_result_empty")
            }

            else -> {
                stringProvider.getString("storage_cleanup_result_failed")
            }
        }
        messageDisplayer.show(message)
    }
}

internal fun mergeStorageUsageWithAppTemp(
    storageUsage: StorageUsageModel?,
    appTempUsage: AppTempCacheUsage,
    appTempTitle: String
): StorageUsageModel? {
    if (appTempUsage.isEmpty) {
        return storageUsage
    }

    val appTempEntry = ChatStorageUsageModel(
        chatId = AppTempChatId,
        chatTitle = appTempTitle,
        size = appTempUsage.size,
        fileCount = appTempUsage.fileCount,
        byFileType = listOf(
            FileTypeStorageUsageModel(
                fileType = AppTempFileType,
                size = appTempUsage.size,
                fileCount = appTempUsage.fileCount
            )
        )
    )

    return if (storageUsage == null) {
        StorageUsageModel(
            totalSize = appTempUsage.size,
            fileCount = appTempUsage.fileCount,
            chatStats = listOf(appTempEntry)
        )
    } else {
        storageUsage.copy(
            totalSize = storageUsage.totalSize + appTempUsage.size,
            fileCount = storageUsage.fileCount + appTempUsage.fileCount,
            chatStats = storageUsage.chatStats + appTempEntry
        )
    }
}

private fun formatStorageSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format(Locale.US, "%.1f %cB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}
