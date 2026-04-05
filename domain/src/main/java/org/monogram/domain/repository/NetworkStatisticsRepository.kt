package org.monogram.domain.repository

import org.monogram.domain.models.NetworkUsageModel

interface NetworkStatisticsRepository {
    suspend fun getNetworkUsage(): NetworkUsageModel?
    suspend fun getNetworkStatisticsEnabled(): Boolean
    suspend fun setNetworkStatisticsEnabled(enabled: Boolean)
    suspend fun resetNetworkStatistics(): Boolean
}