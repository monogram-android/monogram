package org.monogram.domain.repository

import org.monogram.domain.models.StorageCleanupResultModel
import org.monogram.domain.models.StorageUsageBreakdownModel
import org.monogram.domain.models.StorageUsageModel

interface StorageRepository {
    suspend fun getStorageUsage(): StorageUsageModel?
    suspend fun getStorageUsageBreakdown(): StorageUsageBreakdownModel?
    suspend fun clearStorage(chatId: Long? = null): StorageCleanupResultModel
    suspend fun setDatabaseMaintenanceSettings(maxDatabaseSize: Long, maxTimeFromLastAccess: Int): Boolean

    suspend fun getStorageOptimizerEnabled(): Boolean
    suspend fun setStorageOptimizerEnabled(enabled: Boolean)
}