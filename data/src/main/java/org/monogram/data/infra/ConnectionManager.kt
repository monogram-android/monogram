package org.monogram.data.infra

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.drinkless.tdlib.TdApi
import org.monogram.core.DispatcherProvider
import org.monogram.core.ScopeProvider
import org.monogram.data.datasource.remote.ChatRemoteSource
import org.monogram.data.datasource.remote.ProxyRemoteDataSource
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.ConnectionStatus
import kotlin.random.Random

class ConnectionManager(
    private val chatRemoteSource: ChatRemoteSource,
    private val proxyRemoteSource: ProxyRemoteDataSource,
    private val updates: UpdateDispatcher,
    private val appPreferences: AppPreferencesProvider,
    private val dispatchers: DispatcherProvider,
    private val connectivityManager: ConnectivityManager,
    scopeProvider: ScopeProvider
) {
    private val TAG = "ConnectionManager"
    private val scope = scopeProvider.appScope

    private val _connectionStateFlow = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connecting)
    val connectionStateFlow = _connectionStateFlow.asStateFlow()

    private var retryJob: Job? = null
    private var proxyJob: Job? = null
    private var watchdogJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var reconnectAttempts = 0
    private var lastRetryAtMs = 0L
    private var lastStateChangeAtMs = System.currentTimeMillis()

    private val minRetryIntervalMs = 1_200L
    private val maxRetryDelayMs = 60_000L

    init {
        scope.launch {
            updates.connectionState.collect { update -> handleConnectionState(update.state, "update") }
        }

        scope.launch {
            updates.authorizationState.collect { update ->
                if (update.authorizationState is TdApi.AuthorizationStateReady) {
                    runReconnectAttempt("auth_ready", force = true)
                    syncConnectionStateFromTdlib("auth_ready")
                }
            }
        }

        registerNetworkCallback()
        startWatchdog()
        startProxyManagement()

        scope.launch(dispatchers.default) {
            runReconnectAttempt("bootstrap", force = true)
            syncConnectionStateFromTdlib("bootstrap")
        }
    }

    private fun handleConnectionState(state: TdApi.ConnectionState, source: String) {
        val status = when (state) {
            is TdApi.ConnectionStateReady -> ConnectionStatus.Connected
            is TdApi.ConnectionStateConnecting -> ConnectionStatus.Connecting
            is TdApi.ConnectionStateUpdating -> ConnectionStatus.Updating
            is TdApi.ConnectionStateWaitingForNetwork -> ConnectionStatus.WaitingForNetwork
            is TdApi.ConnectionStateConnectingToProxy -> ConnectionStatus.ConnectingToProxy
            else -> ConnectionStatus.Connecting
        }

        val previous = _connectionStateFlow.value
        if (previous != status) {
            lastStateChangeAtMs = System.currentTimeMillis()
            Log.d(TAG, "Connection state changed: $previous -> $status ($source)")
        }

        _connectionStateFlow.value = status

        when (status) {
            is ConnectionStatus.Connected -> {
                reconnectAttempts = 0
                retryJob?.cancel()
                retryJob = null
            }

            is ConnectionStatus.Connecting,
            is ConnectionStatus.Updating,
            is ConnectionStatus.WaitingForNetwork,
            is ConnectionStatus.ConnectingToProxy -> startRetryLoop()
        }
    }

    fun retryConnection() {
        scope.launch(dispatchers.default) {
            runReconnectAttempt("manual", force = true)
            syncConnectionStateFromTdlib("manual")
        }
    }

    private fun startRetryLoop() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch(dispatchers.default) {
            runReconnectAttempt("state_change", force = true)
            syncConnectionStateFromTdlib("state_change")

            while (isActive && _connectionStateFlow.value !is ConnectionStatus.Connected) {
                delay(calculateRetryDelayMs(_connectionStateFlow.value, reconnectAttempts))
                if (!isActive || _connectionStateFlow.value is ConnectionStatus.Connected) break

                runReconnectAttempt("scheduled")
                syncConnectionStateFromTdlib("scheduled")
            }
        }
    }

    private suspend fun runReconnectAttempt(reason: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRetryAtMs < minRetryIntervalMs) return
        lastRetryAtMs = now
        reconnectAttempts++

        Log.d(TAG, "Reconnect attempt #$reconnectAttempts ($reason), state=${_connectionStateFlow.value}")

        val networkTypeUpdated = runCatching {
            withContext(dispatchers.io) {
                chatRemoteSource.setNetworkType()
            }
        }.getOrElse { error ->
            Log.e(TAG, "Reconnect attempt failed", error)
            false
        }

        if (!networkTypeUpdated) {
            Log.w(TAG, "Reconnect attempt did not update network type")
        }

        runCatching {
            withContext(dispatchers.io) {
                proxyRemoteSource.setOption("online", TdApi.OptionValueBoolean(true))
            }
        }

        maybeAdjustProxyOnFailures(force = force || !networkTypeUpdated)
    }

    private suspend fun syncConnectionStateFromTdlib(reason: String) {
        val state = withTimeoutOrNull(4_000L) {
            withContext(dispatchers.io) {
                chatRemoteSource.getConnectionState()
            }
        }

        if (state == null) {
            if (!hasActiveNetwork()) {
                handleConnectionState(TdApi.ConnectionStateWaitingForNetwork(), "probe:$reason:fallback")
            }
            return
        }

        handleConnectionState(state, "probe:$reason")
    }

    private suspend fun maybeAdjustProxyOnFailures(force: Boolean = false) {
        if (!appPreferences.isAutoBestProxyEnabled.value) return

        if (!force) {
            if (reconnectAttempts < 4) return
            if (reconnectAttempts % 3 != 0) return
        }

        runCatching { selectBestProxy() }
            .onFailure { Log.e(TAG, "Proxy fallback failed during reconnect", it) }
    }

    private fun calculateRetryDelayMs(status: ConnectionStatus, attempts: Int): Long {
        val base = when (status) {
            is ConnectionStatus.WaitingForNetwork -> 2_500L
            is ConnectionStatus.ConnectingToProxy -> 3_500L
            is ConnectionStatus.Updating -> 2_000L
            is ConnectionStatus.Connecting -> 1_500L
            is ConnectionStatus.Connected -> 1_000L
        }
        val backoff = (base * (1L shl attempts.coerceAtMost(5))).coerceAtMost(maxRetryDelayMs)
        val jitter = Random.nextLong(200L, 1_200L)
        return backoff + jitter
    }

    private fun startProxyManagement() {
        proxyJob?.cancel()
        proxyJob = scope.launch {
            appPreferences.enabledProxyId.value?.let { proxyId ->
                if (!proxyRemoteSource.enableProxy(proxyId)) {
                    appPreferences.setEnabledProxyId(null)
                }
            }

            while (isActive) {
                if (appPreferences.isAutoBestProxyEnabled.value) {
                    runCatching { selectBestProxy() }
                        .onFailure { Log.e(TAG, "Error selecting best proxy", it) }
                }
                delay(300_000)
            }
        }
    }

    private suspend fun selectBestProxy() {
        val proxies = proxyRemoteSource.getProxies()
        if (proxies.isEmpty()) return

        val best = coroutineScope {
            proxies.map { proxy ->
                async {
                    val ping = withTimeoutOrNull(4_000L) {
                        proxyRemoteSource.pingProxy(proxy.server, proxy.port, proxy.type)
                    } ?: Long.MAX_VALUE
                    proxy to ping
                }
            }.awaitAll()
        }.minByOrNull { it.second } ?: return

        if (best.second == Long.MAX_VALUE) return

        val currentEnabled = proxies.find { it.isEnabled }
        if (best.first.id != currentEnabled?.id) {
            Log.d(TAG, "Switching to better proxy: ${best.first.server}:${best.first.port} (ping: ${best.second}ms)")
            if (proxyRemoteSource.enableProxy(best.first.id)) {
                appPreferences.setEnabledProxyId(best.first.id)
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch(dispatchers.default) {
            while (isActive) {
                delay(15_000L)
                if (_connectionStateFlow.value is ConnectionStatus.Connected) continue

                val stuckForMs = System.currentTimeMillis() - lastStateChangeAtMs
                if (stuckForMs >= 20_000L) {
                    runReconnectAttempt("watchdog", force = true)
                    syncConnectionStateFromTdlib("watchdog")
                }

                if (stuckForMs >= 120_000L) {
                    maybeAdjustProxyOnFailures(force = true)
                }
            }
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onNetworkChanged("available")
            }

            override fun onLost(network: Network) {
                onNetworkChanged("lost")
            }
        }

        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(callback)
            } else {
                connectivityManager.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            }
            true
        }.getOrElse {
            Log.w(TAG, "Failed to register network callback", it)
            false
        }

        if (registered) {
            networkCallback = callback
        }
    }

    private fun onNetworkChanged(reason: String) {
        scope.launch(dispatchers.default) {
            runReconnectAttempt("network_$reason", force = true)
            syncConnectionStateFromTdlib("network_$reason")
        }
    }

    private fun hasActiveNetwork(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val active = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(active) ?: return false
            capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            connectivityManager.activeNetworkInfo?.isConnected == true
        }
    }
}
