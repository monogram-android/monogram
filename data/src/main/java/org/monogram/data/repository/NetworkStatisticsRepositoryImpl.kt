package org.monogram.data.repository

import org.drinkless.tdlib.TdApi
import org.monogram.data.datasource.remote.SettingsRemoteDataSource
import org.monogram.data.mapper.NetworkMapper
import org.monogram.domain.models.NetworkUsageModel
import org.monogram.domain.repository.NetworkStatisticsRepository

class NetworkStatisticsRepositoryImpl(
    private val remote: SettingsRemoteDataSource,
    private val networkMapper: NetworkMapper
) : NetworkStatisticsRepository {

    override suspend fun getNetworkUsage(): NetworkUsageModel? {
        return remote.getNetworkStatistics()?.let { networkMapper.mapToDomain(it) }
    }

    override suspend fun getNetworkStatisticsEnabled(): Boolean {
        val result = remote.getOption("disable_network_statistics")
        return if (result is TdApi.OptionValueBoolean) !result.value else true
    }

    override suspend fun setNetworkStatisticsEnabled(enabled: Boolean) {
        remote.setOption("disable_network_statistics", TdApi.OptionValueBoolean(!enabled))
        remote.setOption("disable_persistent_network_statistics", TdApi.OptionValueBoolean(!enabled))
    }

    override suspend fun resetNetworkStatistics(): Boolean {
        return remote.resetNetworkStatistics()
    }
}