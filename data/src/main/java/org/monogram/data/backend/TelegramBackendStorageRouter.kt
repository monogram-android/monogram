package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.data.mtproto.MtProtoStorageCleanupRepository
import org.monogram.data.mtproto.MtProtoStorageUsageRepository
import org.monogram.domain.models.StorageCleanupResultModel
import org.monogram.domain.models.StorageUsageBreakdownModel
import org.monogram.domain.models.StorageUsageModel
import org.monogram.domain.repository.StorageRepository

/** Keeps TDLib-local storage statistics and cleanup out of selected MTProto accounts. */
internal class TelegramBackendStorageRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> StorageRepository,
    scope: CoroutineScope,
    private val mtProtoCleanupFactory: () -> MtProtoStorageCleanupRepository = { throw UnsupportedOperationException("MTProto storage cleanup is not configured") },
    private val mtProtoUsageFactory: () -> MtProtoStorageUsageRepository = { throw UnsupportedOperationException("MTProto storage usage is not configured") },
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : StorageRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)
    private val mtProtoCleanup by lazy(LazyThreadSafetyMode.NONE, mtProtoCleanupFactory)
    private val mtProtoUsage by lazy(LazyThreadSafetyMode.NONE, mtProtoUsageFactory)

    init {
        scope.launch {
            selectionStore.observe(accountId).collect { selectedBackend.value = it }
        }
    }

    override suspend fun getStorageUsage(): StorageUsageModel? = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getStorageUsage()
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProtoUsage.getDownloadUsage()
    }

    override suspend fun getStorageUsageBreakdown(): StorageUsageBreakdownModel? = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getStorageUsageBreakdown()
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    override suspend fun clearStorage(chatId: Long?): StorageCleanupResultModel = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.clearStorage(chatId)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProtoCleanup.clearCompletedDownloads(chatId)
    }

    override suspend fun setDatabaseMaintenanceSettings(maxDatabaseSize: Long, maxTimeFromLastAccess: Int) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.setDatabaseMaintenanceSettings(maxDatabaseSize, maxTimeFromLastAccess)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    override suspend fun getStorageOptimizerEnabled() = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getStorageOptimizerEnabled()
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    override suspend fun setStorageOptimizerEnabled(enabled: Boolean) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.setStorageOptimizerEnabled(enabled)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    private fun selected(): TelegramBackendKind = checkNotNull(selectedBackend.value) {
        "Telegram backend selection is not loaded"
    }

    private fun unsupported(): Nothing = throw UnsupportedOperationException(
        "MTProto storage management is not available"
    )

    private companion object { const val DEFAULT_ACCOUNT_ID = "default" }
}
