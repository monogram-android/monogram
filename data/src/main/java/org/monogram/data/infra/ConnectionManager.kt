package org.monogram.data.infra

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import org.monogram.core.DispatcherProvider
import org.monogram.data.core.coRunCatching
import org.monogram.data.datasource.remote.ChatRemoteSource
import org.monogram.data.datasource.remote.ProxyRemoteDataSource
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.data.gateway.isExpectedProxyFailure
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.ConnectionStatus
import org.monogram.domain.repository.ProxyNetworkMode
import org.monogram.domain.repository.ProxyNetworkRule
import org.monogram.domain.repository.ProxyNetworkType
import org.monogram.domain.repository.ProxyUnavailableFallback
import org.monogram.domain.repository.defaultProxyNetworkMode
import kotlin.random.Random

class ConnectionManager(
    private val chatRemoteSource: ChatRemoteSource,
    private val proxyRemoteSource: ProxyRemoteDataSource,
    private val updates: UpdateDispatcher,
    private val appPreferences: AppPreferencesProvider,
    private val dispatchers: DispatcherProvider,
    private val connectivityManager: ConnectivityManager,
    private val scope: CoroutineScope
) {
    private val tag = "ConnectionManager"

    private val _connectionStateFlow = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connecting)
    val connectionStateFlow = _connectionStateFlow.asStateFlow()

    private var retryJob: Job? = null
    private var proxyModeWatcherJob: Job? = null
    private var autoBestJob: Job? = null
    private var watchdogJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var reconnectAttempts = 0
    private var lastRetryAtMs = 0L
    private var lastStateChangeAtMs = System.currentTimeMillis()
    private val proxyRuleMutex = Mutex()

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
            Log.d(tag, "Connection state changed: $previous -> $status ($source)")
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

        Log.d(
            tag,
            "Reconnect attempt #$reconnectAttempts ($reason), state=${_connectionStateFlow.value}"
        )

        val networkTypeUpdated = coRunCatching {
            withContext(dispatchers.io) {
                chatRemoteSource.setNetworkType()
            }
        }.getOrElse { error ->
            Log.e(tag, "Reconnect attempt failed", error)
            false
        }

        if (!networkTypeUpdated) {
            Log.w(tag, "Reconnect attempt did not update network type")
        }

        coRunCatching {
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
        val isAutoBestEnabled = appPreferences.isAutoBestProxyEnabled.value
        if (!isAutoBestEnabled) return

        if (!force) {
            if (reconnectAttempts < 4) return
            if (reconnectAttempts % 3 != 0) return
        }

        applyNetworkProxyRuleSafely("reconnect_failures")
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
        proxyModeWatcherJob?.cancel()
        proxyModeWatcherJob = scope.launch {
            syncEnabledProxyPreferenceFromTdlib("startup_sync")

            appPreferences.enabledProxyId.value?.let { proxyId ->
                if (!enableProxy(proxyId, getCurrentNetworkType(), "startup_restore")) {
                    appPreferences.setEnabledProxyId(null)
                }
            }

            applyNetworkProxyRuleSafely("startup")

            launch {
                appPreferences.proxyNetworkRules.collect {
                    applyNetworkProxyRuleSafely("rules_changed")
                }
            }

            launch {
                appPreferences.proxyUnavailableFallback.collect {
                    applyNetworkProxyRuleSafely("fallback_changed")
                }
            }

            launch {
                appPreferences.preferIpv6.collect { preferIpv6 ->
                    coRunCatching {
                        proxyRemoteSource.setOption(
                            "prefer_ipv6",
                            TdApi.OptionValueBoolean(preferIpv6)
                        )
                    }.onFailure { error ->
                        if (error.isExpectedProxyFailure()) {
                            Log.w(tag, "Failed to apply prefer_ipv6 option: ${error.message}")
                        } else {
                            Log.e(tag, "Failed to apply prefer_ipv6 option", error)
                        }
                    }
                }
            }

            launch {
                appPreferences.isAutoBestProxyEnabled.collect { autoBest ->
                    autoBestJob?.cancel()

                    if (autoBest) {
                        autoBestJob = launchAutoBestLoop()
                    }
                }
            }
        }
    }

    private suspend fun syncEnabledProxyPreferenceFromTdlib(reason: String) {
        coRunCatching { proxyRemoteSource.getProxies() }
            .onSuccess { proxies ->
                val enabledId = proxies.firstOrNull { it.isEnabled }?.id
                if (appPreferences.enabledProxyId.value != enabledId) {
                    Log.d(
                        tag,
                        "Syncing enabled proxy id from TDLib ($reason): ${appPreferences.enabledProxyId.value} -> $enabledId"
                    )
                    appPreferences.setEnabledProxyId(enabledId)
                }
            }
            .onFailure { error ->
                if (error.isExpectedProxyFailure()) {
                    Log.w(tag, "Failed to sync enabled proxy id ($reason): ${error.message}")
                } else {
                    Log.e(tag, "Failed to sync enabled proxy id ($reason)", error)
                }
            }
    }

    private fun launchAutoBestLoop(): Job = scope.launch(dispatchers.default) {
        while (isActive) {
            applyNetworkProxyRuleSafely("auto_best_loop")
            delay(300_000L)
        }
    }

    private suspend fun applyNetworkProxyRuleSafely(reason: String) {
        coRunCatching { applyNetworkProxyRule(reason) }
            .onFailure { error ->
                if (error.isExpectedProxyFailure()) {
                    Log.w(tag, "Proxy rule apply failed ($reason): ${error.message}")
                } else {
                    Log.e(tag, "Error applying proxy rule ($reason)", error)
                }
            }
    }

    private suspend fun applyNetworkProxyRule(reason: String) {
        proxyRuleMutex.withLock {
            val networkType = getCurrentNetworkType()
            val rule = appPreferences.proxyNetworkRules.value[networkType]
                ?: ProxyNetworkRule(defaultProxyNetworkMode(networkType))

            when (rule.mode) {
                ProxyNetworkMode.DIRECT -> {
                    disableProxyIfNeeded("$reason:direct")
                }

                ProxyNetworkMode.BEST_PROXY -> {
                    selectBestProxy(networkType, "$reason:best")
                }

                ProxyNetworkMode.LAST_USED -> {
                    val target = rule.lastUsedProxyId
                    if (target != null && enableProxy(
                            target,
                            networkType,
                            "$reason:last_used"
                        )
                    ) return
                    handleUnavailableFallback(networkType, "$reason:last_used")
                }

                ProxyNetworkMode.SPECIFIC_PROXY -> {
                    val target = rule.specificProxyId
                    if (target != null && enableProxy(
                            target,
                            networkType,
                            "$reason:specific"
                        )
                    ) return
                    handleUnavailableFallback(networkType, "$reason:specific")
                }
            }
        }
    }

    private suspend fun handleUnavailableFallback(networkType: ProxyNetworkType, reason: String) {
        when (appPreferences.proxyUnavailableFallback.value) {
            ProxyUnavailableFallback.BEST_PROXY -> selectBestProxy(
                networkType,
                "$reason:fallback_best"
            )

            ProxyUnavailableFallback.DIRECT -> disableProxyIfNeeded("$reason:fallback_direct")
            ProxyUnavailableFallback.KEEP_CURRENT -> Unit
        }
    }

    private suspend fun selectBestProxy(networkType: ProxyNetworkType, reason: String): Boolean {
        val proxies = coRunCatching { proxyRemoteSource.getProxies() }
            .onFailure { error ->
                if (error.isExpectedProxyFailure()) {
                    Log.w(tag, "Failed to load proxies ($reason): ${error.message}")
                } else {
                    Log.e(tag, "Failed to load proxies ($reason)", error)
                }
            }
            .getOrElse { emptyList() }
        if (proxies.isEmpty()) {
            disableProxyIfNeeded("$reason:no_proxies")
            return false
        }

        val best = coroutineScope {
            proxies.map { proxy ->
                async {
                    val ping = coRunCatching {
                        withTimeoutOrNull(4_000L) {
                            proxyRemoteSource.pingProxy(proxy.server, proxy.port, proxy.type)
                        } ?: Long.MAX_VALUE
                    }.getOrElse { error ->
                        if (error.isExpectedProxyFailure()) {
                            Log.w(
                                tag,
                                "Ping failed for ${proxy.server}:${proxy.port} ($reason): ${error.message}"
                            )
                        } else {
                            Log.e(
                                tag,
                                "Ping failed for ${proxy.server}:${proxy.port} ($reason)",
                                error
                            )
                        }
                        Long.MAX_VALUE
                    }
                    proxy to ping
                }
            }.awaitAll()
        }.minByOrNull { it.second } ?: return false

        if (best.second == Long.MAX_VALUE) {
            Log.w(tag, "All proxies are unreachable, switching to direct connection")
            disableProxyIfNeeded("$reason:all_unreachable")
            return false
        }

        val currentEnabled = proxies.find { it.isEnabled }
        if (best.first.id != currentEnabled?.id) {
            Log.d(
                tag,
                "Switching to best proxy ${best.first.server}:${best.first.port} (${best.second}ms) ($reason)"
            )
            return enableProxy(best.first.id, networkType, "$reason:switch")
        }

        appPreferences.setLastUsedProxyIdForNetwork(networkType, best.first.id)
        return true
    }

    private suspend fun enableProxy(
        proxyId: Int,
        networkType: ProxyNetworkType,
        reason: String
    ): Boolean {
        val enabled = coRunCatching {
            withContext(dispatchers.io) {
                proxyRemoteSource.enableProxy(proxyId)
            }
        }.getOrDefault(false)

        if (enabled) {
            appPreferences.setEnabledProxyId(proxyId)
            appPreferences.setLastUsedProxyIdForNetwork(networkType, proxyId)
        } else {
            Log.w(tag, "Failed to enable proxy $proxyId ($reason)")
        }

        return enabled
    }

    private suspend fun disableProxyIfNeeded(reason: String): Boolean {
        if (appPreferences.enabledProxyId.value == null) return true

        val disabled = coRunCatching {
            withContext(dispatchers.io) {
                proxyRemoteSource.disableProxy()
            }
            true
        }.getOrDefault(false)

        if (disabled) {
            appPreferences.setEnabledProxyId(null)
        } else {
            Log.w(tag, "Failed to disable proxy ($reason)")
        }

        return disabled
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

        val registered = coRunCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(callback)
            } else {
                connectivityManager.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            }
            true
        }.getOrElse {
            Log.w(tag, "Failed to register network callback", it)
            false
        }

        if (registered) {
            networkCallback = callback
        }
    }

    private fun onNetworkChanged(reason: String) {
        scope.launch(dispatchers.default) {
            applyNetworkProxyRuleSafely("network_$reason")
            runReconnectAttempt("network_$reason", force = true)
            syncConnectionStateFromTdlib("network_$reason")
        }
    }

    private fun getCurrentNetworkType(): ProxyNetworkType {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val active = connectivityManager.activeNetwork ?: return ProxyNetworkType.OTHER
            val capabilities =
                connectivityManager.getNetworkCapabilities(active) ?: return ProxyNetworkType.OTHER
            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> ProxyNetworkType.VPN
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ProxyNetworkType.WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ProxyNetworkType.MOBILE
                else -> ProxyNetworkType.OTHER
            }
        } else {
            @Suppress("DEPRECATION")
            when (connectivityManager.activeNetworkInfo?.type) {
                ConnectivityManager.TYPE_VPN -> ProxyNetworkType.VPN
                ConnectivityManager.TYPE_WIFI -> ProxyNetworkType.WIFI
                ConnectivityManager.TYPE_MOBILE -> ProxyNetworkType.MOBILE
                else -> ProxyNetworkType.OTHER
            }
        }
    }

    private fun hasActiveNetwork(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val active = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(active) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            connectivityManager.activeNetworkInfo?.isConnected == true
        }
    }
}
