package org.monogram.data.mapper

import android.util.Log
import org.drinkless.tdlib.TdApi
import org.monogram.domain.models.NetworkTypeUsage
import org.monogram.domain.models.NetworkUsageCategory
import org.monogram.domain.models.NetworkUsageModel
import org.monogram.domain.repository.StringProvider

class NetworkMapper(
    private val stringProvider: StringProvider,
    private val storageMapper: StorageMapper
) {
    fun mapToDomain(statistics: TdApi.NetworkStatistics): NetworkUsageModel {
        val mobileDetails = mutableMapOf<String, Pair<Long, Long>>()
        val wifiDetails = mutableMapOf<String, Pair<Long, Long>>()
        val roamingDetails = mutableMapOf<String, Pair<Long, Long>>()
        val otherDetails = mutableMapOf<String, Pair<Long, Long>>()

        var mobileSent = 0L
        var mobileReceived = 0L
        var wifiSent = 0L
        var wifiReceived = 0L
        var roamingSent = 0L
        var roamingReceived = 0L
        var otherSent = 0L
        var otherReceived = 0L

        statistics.entries.forEachIndexed { index, entry ->
            val categoryName = if (entry is TdApi.NetworkStatisticsEntryFile) {
                storageMapper.mapFileTypeToDomain(entry.fileType)
            } else {
                stringProvider.getString("network_calls")
            }
            val sent = if (entry is TdApi.NetworkStatisticsEntryFile) entry.sentBytes else if (entry is TdApi.NetworkStatisticsEntryCall) entry.sentBytes else 0L
            val received = if (entry is TdApi.NetworkStatisticsEntryFile) entry.receivedBytes else if (entry is TdApi.NetworkStatisticsEntryCall) entry.receivedBytes else 0L

            val networkType = when (entry) {
                is TdApi.NetworkStatisticsEntryFile -> entry.networkType
                is TdApi.NetworkStatisticsEntryCall -> entry.networkType
                else -> null
            }

            Log.d(
                "NetworkMapper",
                "Processing entry $index: category=$categoryName, sent=$sent, received=$received, type=${networkType?.javaClass?.simpleName}"
            )

            when (networkType) {
                is TdApi.NetworkTypeMobile -> {
                    mobileSent += sent
                    mobileReceived += received
                    val current = mobileDetails[categoryName] ?: (0L to 0L)
                    mobileDetails[categoryName] = (current.first + sent) to (current.second + received)
                    Log.d("NetworkMapper", "-> Added to Mobile")
                }
                is TdApi.NetworkTypeWiFi -> {
                    wifiSent += sent
                    wifiReceived += received
                    val current = wifiDetails[categoryName] ?: (0L to 0L)
                    wifiDetails[categoryName] = (current.first + sent) to (current.second + received)
                    Log.d("NetworkMapper", "-> Added to WiFi")
                }
                is TdApi.NetworkTypeMobileRoaming -> {
                    roamingSent += sent
                    roamingReceived += received
                    val current = roamingDetails[categoryName] ?: (0L to 0L)
                    roamingDetails[categoryName] = (current.first + sent) to (current.second + received)
                    Log.d("NetworkMapper", "-> Added to Roaming")
                }

                else -> {
                    otherSent += sent
                    otherReceived += received
                    val current = otherDetails[categoryName] ?: (0L to 0L)
                    otherDetails[categoryName] = (current.first + sent) to (current.second + received)
                    Log.d("NetworkMapper", "-> Added to Other (type was ${networkType?.javaClass?.simpleName})")
                }
            }
        }

        return NetworkUsageModel(
            mobile = NetworkTypeUsage(mobileSent, mobileReceived, mobileDetails.map { (k, v) -> NetworkUsageCategory(k, v.first, v.second) }),
            wifi = NetworkTypeUsage(wifiSent, wifiReceived, wifiDetails.map { (k, v) -> NetworkUsageCategory(k, v.first, v.second) }),
            roaming = NetworkTypeUsage(
                roamingSent,
                roamingReceived,
                roamingDetails.map { (k, v) -> NetworkUsageCategory(k, v.first, v.second) }),
            other = NetworkTypeUsage(
                otherSent,
                otherReceived,
                otherDetails.map { (k, v) -> NetworkUsageCategory(k, v.first, v.second) })
        )
    }
}
