package org.monogram.data.infra

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
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
    scopeProvider: ScopeProvider
) {
    private val TAG = "ConnectionManager"
    private val scope = scopeProvider.appScope

    private val _connectionStateFlow = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connecting)
    val connectionStateFlow = _connectionStateFlow.asStateFlow()

    private var retryJob: Job? = null
    private var proxyJob: Job? = null
    private var reconnectAttempts = 0
    private var lastRetryAtMs = 0L

    private val minRetryIntervalMs = 1_200L
    private val maxRetryDelayMs = 60_000L

    init {
        scope.launch {
            updates.chatsListUpdates
                .filterIsInstance<TdApi.UpdateConnectionState>()
                .collect { update -> handleConnectionState(update.state) }
        }

        startProxyManagement()
    }

    private fun handleConnectionState(state: TdApi.ConnectionState) {
        val status = when (state) {
            is TdApi.ConnectionStateReady -> ConnectionStatus.Connected
            is TdApi.ConnectionStateConnecting -> ConnectionStatus.Connecting
            is TdApi.ConnectionStateUpdating -> ConnectionStatus.Updating
            is TdApi.ConnectionStateWaitingForNetwork -> ConnectionStatus.WaitingForNetwork
            is TdApi.ConnectionStateConnectingToProxy -> ConnectionStatus.ConnectingToProxy
            else -> ConnectionStatus.Connecting
        }
        Log.d(TAG, "Connection state changed: $status")
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
            runReconnectAttempt("manual")
        }
    }

    private fun startRetryLoop() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch(dispatchers.default) {
            runReconnectAttempt("state_change")
            while (isActive && _connectionStateFlow.value !is ConnectionStatus.Connected) {
                delay(calculateRetryDelayMs(_connectionStateFlow.value, reconnectAttempts))
                if (!isActive || _connectionStateFlow.value is ConnectionStatus.Connected) break
                runReconnectAttempt("scheduled")
            }
        }
    }

    private suspend fun runReconnectAttempt(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastRetryAtMs < minRetryIntervalMs) return
        lastRetryAtMs = now
        reconnectAttempts++

        Log.d(TAG, "Reconnect attempt #$reconnectAttempts ($reason), state=${_connectionStateFlow.value}")
        runCatching {
            withContext(dispatchers.io) {
                chatRemoteSource.setNetworkType()
            }
        }.onFailure { error ->
            Log.e(TAG, "Reconnect attempt failed", error)
        }

        maybeAdjustProxyOnFailures()
    }

    private suspend fun maybeAdjustProxyOnFailures() {
        if (!appPreferences.isAutoBestProxyEnabled.value) return
        if (reconnectAttempts < 4) return
        if (reconnectAttempts % 3 != 0) return

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
            // Initial proxy enable if needed
            appPreferences.enabledProxyId.value?.let { proxyId ->
                proxyRemoteSource.enableProxy(proxyId)
            }

            while (isActive) {
                if (appPreferences.isAutoBestProxyEnabled.value) {
                    runCatching { selectBestProxy() }
                        .onFailure { Log.e(TAG, "Error selecting best proxy", it) }
                }
                delay(300_000) // 5 minutes
            }
        }
    }

    private suspend fun selectBestProxy() {
        val proxies = proxyRemoteSource.getProxies()
        if (proxies.isEmpty()) return

        val best = coroutineScope {
            proxies.map { proxy ->
                async { proxy to proxyRemoteSource.pingProxy(proxy.server, proxy.port, proxy.type) }
            }.awaitAll()
        }.minByOrNull { it.second } ?: return

        if (best.second == Long.MAX_VALUE) return

        val currentEnabled = proxies.find { it.isEnabled }
        if (best.first != currentEnabled) {
            Log.d(TAG, "Switching to better proxy: ${best.first.server}:${best.first.port} (ping: ${best.second}ms)")
            if (proxyRemoteSource.enableProxy(best.first.id)) {
                appPreferences.setEnabledProxyId(best.first.id)
            }
        }
    }
}
