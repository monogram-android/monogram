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

        retryJob?.cancel()
        if (status is ConnectionStatus.WaitingForNetwork) {
            retryJob = scope.launch {
                delay(5000)
                while (isActive) {
                    retryConnection()
                    delay(10000)
                }
            }
        }
    }

    fun retryConnection() {
        scope.launch(dispatchers.io) {
            chatRemoteSource.setNetworkType()
        }
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
