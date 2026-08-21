package org.monogram.data.mtproto

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.monogram.data.db.dao.KeyValueDao
import org.monogram.data.db.model.KeyValueEntity
import org.monogram.domain.models.NetworkUsageCategory
import org.monogram.domain.models.NetworkUsageModel
import org.monogram.domain.models.NetworkTypeUsage
import org.monogram.domain.repository.NetworkStatisticsRepository
import org.monogram.mtproto.transport.MtProtoTrafficListener

internal interface MtProtoNetworkStatisticsRepository : NetworkStatisticsRepository {
    /** Traffic sink handed to the MTProto transport factory. */
    val trafficListener: MtProtoTrafficListener
}

/**
 * Tracks MTProto transport bytes per Android network type, persisted locally.
 *
 * Mirrors the TDLib-local network statistics contract without any server interaction.
 */
internal class MtProtoNetworkStatisticsRepositoryImpl(
    private val keyValueDao: KeyValueDao,
    private val networkType: () -> NetworkType,
    recordingScope: CoroutineScope? = null,
) : MtProtoNetworkStatisticsRepository {
    private val mutex = Mutex()
    private val scope = recordingScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val trafficListener = MtProtoTrafficListener { sentBytes, receivedBytes ->
        if (sentBytes > 0 || receivedBytes > 0) {
            val type = networkType()
            scope.launch { record(type, sentBytes.toLong(), receivedBytes.toLong()) }
        }
    }

    override suspend fun getNetworkUsage(): NetworkUsageModel {
        val stored = mutex.withLock { load() }
        return NetworkUsageModel(
            mobile = usage(stored, NetworkType.MOBILE),
            wifi = usage(stored, NetworkType.WIFI),
            roaming = usage(stored, NetworkType.ROAMING),
            other = usage(stored, NetworkType.OTHER),
        )
    }

    override suspend fun getNetworkStatisticsEnabled(): Boolean =
        readEnabled().let { it ?: true }

    override suspend fun setNetworkStatisticsEnabled(enabled: Boolean) {
        keyValueDao.insertValue(KeyValueEntity(KEY_ENABLED, enabled.toString()))
    }

    override suspend fun resetNetworkStatistics(): Boolean {
        mutex.withLock {
            NetworkType.entries.forEach { type ->
                keyValueDao.deleteValue(sentKey(type))
                keyValueDao.deleteValue(receivedKey(type))
            }
            keyValueDao.deleteValue(KEY_ENABLED)
        }
        return true
    }

    private suspend fun record(type: NetworkType, sentBytes: Long, receivedBytes: Long) {
        if (readEnabled() == false) return
        mutex.withLock {
            val sentKey = sentKey(type)
            val receivedKey = receivedKey(type)
            val sent = keyValueDao.getValue(sentKey)?.value?.toLongOrNull() ?: 0L
            val received = keyValueDao.getValue(receivedKey)?.value?.toLongOrNull() ?: 0L
            keyValueDao.insertValue(KeyValueEntity(sentKey, (sent + sentBytes).toString()))
            keyValueDao.insertValue(KeyValueEntity(receivedKey, (received + receivedBytes).toString()))
        }
    }

    private suspend fun load(): Map<NetworkType, Pair<Long, Long>> = NetworkType.entries.associateWith { type ->
        val sent = keyValueDao.getValue(sentKey(type))?.value?.toLongOrNull() ?: 0L
        val received = keyValueDao.getValue(receivedKey(type))?.value?.toLongOrNull() ?: 0L
        sent to received
    }

    private suspend fun readEnabled(): Boolean? =
        keyValueDao.getValue(KEY_ENABLED)?.value?.toBooleanStrictOrNull()

    private fun usage(stored: Map<NetworkType, Pair<Long, Long>>, type: NetworkType): NetworkTypeUsage {
        val (sent, received) = stored.getValue(type)
        return NetworkTypeUsage(
            sent = sent,
            received = received,
            details = if (sent == 0L && received == 0L) {
                emptyList()
            } else {
                listOf(NetworkUsageCategory(CATEGORY_NAME, sent, received))
            },
        )
    }

    private fun sentKey(type: NetworkType) = "$KEY_PREFIX${type.name.lowercase()}_sent"
    private fun receivedKey(type: NetworkType) = "$KEY_PREFIX${type.name.lowercase()}_received"

    private companion object {
        const val KEY_PREFIX = "mtproto_network_stats_v1_"
        const val KEY_ENABLED = "${KEY_PREFIX}enabled"
        const val CATEGORY_NAME = "MTProto"
    }
}

internal enum class NetworkType { MOBILE, WIFI, ROAMING, OTHER }

/** Classifies the current active Android network into a statistics bucket. */
internal fun ConnectivityManager.currentNetworkType(): NetworkType {
    val network = activeNetwork ?: return NetworkType.OTHER
    val capabilities = getNetworkCapabilities(network) ?: return NetworkType.OTHER
    return when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.MOBILE
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
        else -> NetworkType.OTHER
    }
}
