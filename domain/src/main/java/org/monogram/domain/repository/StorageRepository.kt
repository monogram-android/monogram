package org.monogram.domain.repository

import org.monogram.domain.models.StorageUsageModel

interface StorageRepository {
    suspend fun getStorageUsage(): StorageUsageModel?
    suspend fun clearStorage(chatId: Long? = null): Boolean
    suspend fun setDatabaseMaintenanceSettings(maxDatabaseSize: Long, maxTimeFromLastAccess: Int): Boolean

    suspend fun getStorageOptimizerEnabled(): Boolean
    suspend fun setStorageOptimizerEnabled(enabled: Boolean)
}