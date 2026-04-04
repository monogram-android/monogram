package org.monogram.data.infra

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.monogram.domain.infra.VpnDetector as VpnDetectorInterface

class VpnDetector(
    private val connectivityManager: ConnectivityManager
) : VpnDetectorInterface {
    private val TAG = "VpnDetector"
    private val _isVpnActive = MutableStateFlow(false)
    override val isVpnActive: StateFlow<Boolean> = _isVpnActive.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastCheckAtElapsedMs = 0L
    private val minCheckIntervalMs = 500L

    override fun startMonitoring() {
        if (networkCallback != null) return

        checkVpnStatus()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                checkVpnStatus()
            }

            override fun onLost(network: Network) {
                checkVpnStatus()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                if (connectivityManager.activeNetwork == network) {
                    checkVpnStatus()
                }
            }
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(callback)
            } else {
                val request = NetworkRequest.Builder().build()
                connectivityManager.registerNetworkCallback(request, callback)
            }
            networkCallback = callback
        }.onFailure { throwable ->
            Log.e(TAG, "Failed to register network callback -- VPN detection disabled", throwable)
        }
    }

    override fun stopMonitoring() {
        networkCallback?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
            networkCallback = null
        }
    }

    private fun checkVpnStatus() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCheckAtElapsedMs < minCheckIntervalMs) return
        lastCheckAtElapsedMs = now

        val isActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = connectivityManager.activeNetwork
            if (activeNetwork != null) {
                val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            } else {
                false
            }
        } else {
            @Suppress("DEPRECATION")
            connectivityManager.activeNetworkInfo?.type == ConnectivityManager.TYPE_VPN
        }

        if (_isVpnActive.value != isActive) {
            _isVpnActive.value = isActive
        }
    }
}
