package org.monogram.data.infra

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.monogram.data.core.coRunCatching
import org.monogram.domain.repository.ProxyNetworkType

internal class ConnectivityNetworkSnapshotProvider(
    private val connectivityManager: ConnectivityManager
) : NetworkSnapshotProvider {
    private val tag = "NetworkSnapshot"

    private val _snapshot = MutableStateFlow(readSnapshot())
    override val snapshot: StateFlow<NetworkSnapshot> = _snapshot.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    init {
        registerCallback()
    }

    private fun registerCallback() {
        if (callback != null) return

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refreshSnapshot("available")
            }

            override fun onLost(network: Network) {
                refreshSnapshot("lost")
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                refreshSnapshot("capabilities")
            }

            override fun onUnavailable() {
                refreshSnapshot("unavailable")
            }
        }

        val registered = coRunCatching {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            true
        }.getOrElse { error ->
            Log.w(tag, "Failed to register default network callback", error)
            false
        }

        if (registered) {
            callback = networkCallback
        }
    }

    private fun refreshSnapshot(reason: String) {
        val updated = readSnapshot()
        if (_snapshot.value == updated) return

        Log.d(tag, "Network snapshot changed ($reason): ${_snapshot.value} -> $updated")
        _snapshot.value = updated
    }

    private fun readSnapshot(): NetworkSnapshot {
        val activeNetwork = connectivityManager.activeNetwork ?: return NetworkSnapshot.Unavailable
        val capabilities =
            connectivityManager.getNetworkCapabilities(activeNetwork) ?: return NetworkSnapshot(
                isAvailable = true,
                isUsable = false,
                type = ProxyNetworkType.OTHER,
                networkId = activeNetwork.hashCode()
            )

        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val type = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> ProxyNetworkType.VPN
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ProxyNetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ProxyNetworkType.MOBILE
            else -> ProxyNetworkType.OTHER
        }

        return NetworkSnapshot(
            isAvailable = true,
            isUsable = hasInternet && isValidated,
            type = type,
            networkId = activeNetwork.hashCode()
        )
    }
}
