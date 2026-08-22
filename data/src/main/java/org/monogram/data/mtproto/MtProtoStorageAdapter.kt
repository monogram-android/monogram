package org.monogram.data.mtproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.data.db.dao.KeyValueDao
import org.monogram.data.db.model.KeyValueEntity
import org.monogram.domain.models.StorageCleanupResultModel
import org.monogram.domain.models.StorageUsageBreakdownModel
import org.monogram.domain.models.StorageUsageModel
import org.monogram.domain.repository.StorageRepository

/** Keeps Telegram-local storage statistics and cleanup out of selected MTProto accounts. */
internal class MtProtoStorageAdapter(
    private val mtProtoCleanupFactory: () -> MtProtoStorageCleanupRepository,
    private val mtProtoUsageFactory: () -> MtProtoStorageUsageRepository,
    private val keyValues: KeyValueDao,
) : StorageRepository {
    private val mtProtoCleanup by lazy(LazyThreadSafetyMode.NONE, mtProtoCleanupFactory)
    private val mtProtoUsage by lazy(LazyThreadSafetyMode.NONE, mtProtoUsageFactory)

    override suspend fun getStorageUsage(): StorageUsageModel? = mtProtoUsage.getDownloadUsage()

    override suspend fun getStorageUsageBreakdown(): StorageUsageBreakdownModel? =
        mtProtoUsage.getDownloadUsage().let { usage ->
            StorageUsageBreakdownModel(
                mediaCacheSize = usage.totalSize,
                databaseSize = 0L,
                logsSize = 0L,
                languagePackDatabaseSize = 0L,
            )
        }

    override suspend fun clearStorage(chatId: Long?): StorageCleanupResultModel = mtProtoCleanup.clearCompletedDownloads(chatId)

    override suspend fun setDatabaseMaintenanceSettings(maxDatabaseSize: Long, maxTimeFromLastAccess: Int): Boolean {
        require(maxDatabaseSize >= 0L) { "Maximum database size must not be negative" }
        require(maxTimeFromLastAccess >= 0) { "Maximum access age must not be negative" }
        keyValues.insertValue(KeyValueEntity(MAINTENANCE_SIZE_KEY, maxDatabaseSize.toString()))
        keyValues.insertValue(KeyValueEntity(MAINTENANCE_AGE_KEY, maxTimeFromLastAccess.toString()))
        return true
    }

    override suspend fun getStorageOptimizerEnabled(): Boolean =
        keyValues.getValue(OPTIMIZER_KEY)?.value?.toBooleanStrictOrNull() ?: true

    override suspend fun setStorageOptimizerEnabled(enabled: Boolean) {
        keyValues.insertValue(KeyValueEntity(OPTIMIZER_KEY, enabled.toString()))
    }

    private companion object {
        const val OPTIMIZER_KEY = "mtproto_storage_optimizer_enabled_v1"
        const val MAINTENANCE_SIZE_KEY = "mtproto_storage_maintenance_size_v1"
        const val MAINTENANCE_AGE_KEY = "mtproto_storage_maintenance_age_v1"
    }
}
