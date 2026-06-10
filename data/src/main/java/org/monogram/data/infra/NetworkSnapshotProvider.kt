package org.monogram.data.infra

import kotlinx.coroutines.flow.StateFlow
import org.monogram.domain.repository.ProxyNetworkType

data class NetworkSnapshot(
    val isAvailable: Boolean,
    val isUsable: Boolean,
    val type: ProxyNetworkType,
    val networkId: Int?
) {
    companion object {
        val Unavailable = NetworkSnapshot(
            isAvailable = false,
            isUsable = false,
            type = ProxyNetworkType.OTHER,
            networkId = null
        )
    }
}

interface NetworkSnapshotProvider {
    val snapshot: StateFlow<NetworkSnapshot>
}
