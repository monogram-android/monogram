package org.monogram.data.infra

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
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
import org.monogram.domain.repository.MAX_SMART_SWITCH_CHECK_INTERVAL_MINUTES
import org.monogram.domain.repository.MIN_SMART_SWITCH_CHECK_INTERVAL_MINUTES
import org.monogram.domain.repository.ProxyNetworkMode
import org.monogram.domain.repository.ProxyNetworkRule
import org.monogram.domain.repository.ProxyNetworkType
import org.monogram.domain.repository.ProxySmartSwitchMode
import org.monogram.domain.repository.ProxyUnavailableFallback
import org.monogram.domain.repository.defaultProxyNetworkMode
import kotlin.random.Random

class ConnectionManager(
    private val chatRemoteSource: ChatRemoteSource,
    private val proxyRemoteSource: ProxyRemoteDataSource,
    private val updates: UpdateDispatcher,
    private val appPreferences: AppPreferencesProvider,
    private val dispatchers: DispatcherProvider,
    private val networkSnapshotProvider: NetworkSnapshotProvider,
    private val appForegroundTracker: AppForegroundTracker,
    private val scope: CoroutineScope
) {
    private val tag = "ConnectionManager"

    private val _connectionStateFlow = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connecting)
    val connectionStateFlow: StateFlow<ConnectionStatus> = _connectionStateFlow.asStateFlow()

    internal val presenceOnlineFlow: StateFlow<Boolean> get() = presenceCoordinator.presenceFlow

    private val statusStabilizer = ConnectionStatusStabilizer(ConnectionStatus.Connecting)
    private val authorizationReady = MutableStateFlow(false)
    private val reconnectMutex = Mutex()
    private val proxyApplyMutex = Mutex()

    private var pendingStatusJob: Job? = null
    private var proxyModeWatcherJob: Job? = null
    private var autoBestJob: Job? = null
    private var watchdogJob: Job? = null
    private var retryJob: Job? = null

    private var pendingStatusDueAtMs: Long? = null
    private var lastStateChangeAtMs = System.currentTimeMillis()
    private var reconnectAttempts = 0
    private var failureStreak = 0
    private var lastEffectiveNetworkType: ProxyNetworkType? = null
    private var lastObservedNetworkSnapshot = networkSnapshotProvider.snapshot.value

    private val reconnectRequests = Channel<ReconnectRequest>(
        capacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val presenceCoordinator = PresenceCoordinator(
        proxyRemoteSource = proxyRemoteSource,
        authorizationReady = authorizationReady,
        appForegroundTracker = appForegroundTracker,
        networkSnapshotProvider = networkSnapshotProvider,
        dispatchers = dispatchers,
        scope = scope
    )

    init {
        appForegroundTracker.start()
        startReconnectProcessor()
        observeAuthorization()
        observeConnectionState()
        observeNetworkSnapshots()
        startProxyManagement()
        startWatchdog()

        requestReconnect("bootstrap", syncAfter = true)
    }

    fun retryConnection() {
        requestReconnect("manual_retry", syncAfter = true)
    }

    private fun observeAuthorization() {
        scope.launch(dispatchers.default) {
            updates.authorizationState.collect { update ->
                val isReady = update.authorizationState is TdApi.AuthorizationStateReady
                authorizationReady.value = isReady

                if (isReady) {
                    requestReconnect("auth_ready", syncAfter = true)
                } else {
                    failureStreak = 0
                }
            }
        }
    }

    private fun observeConnectionState() {
        scope.launch(dispatchers.default) {
            updates.connectionState.collect { update ->
                handleConnectionState(update.state, "update")
            }
        }
    }

    private fun observeNetworkSnapshots() {
        scope.launch(dispatchers.default) {
            networkSnapshotProvider.snapshot.collect { snapshot ->
                val previous = lastObservedNetworkSnapshot
                lastObservedNetworkSnapshot = snapshot

                val typeChanged = previous.type != snapshot.type
                val networkChanged = previous.networkId != snapshot.networkId
                val usableBecameAvailable = !previous.isUsable && snapshot.isUsable
                val usableLost = previous.isUsable && !snapshot.isUsable

                if (usableLost) {
                    handleConnectionState(
                        TdApi.ConnectionStateWaitingForNetwork(),
                        "network_unusable"
                    )
                }

                if (typeChanged) {
                    applyProxyForReason("network_type_changed", force = true)
                }

                if (usableBecameAvailable || networkChanged || usableLost) {
                    requestReconnect(
                        reason = when {
                            usableBecameAvailable -> "network_usable"
                            usableLost -> "network_lost"
                            else -> "network_changed"
                        },
                        syncAfter = true
                    )
                }
            }
        }
    }

    private fun handleConnectionState(state: TdApi.ConnectionState, source: String) {
        val rawStatus = state.toConnectionStatus()
        val now = System.currentTimeMillis()
        val stabilized = statusStabilizer.onStatus(
            rawStatus = rawStatus,
            nowMs = now,
            hasUsableNetwork = lastObservedNetworkSnapshot.isUsable
        )
        val publishedStatus = stabilized.status

        if (publishedStatus == null) {
            schedulePendingStatus(stabilized.pendingDueAtMs, source)
            Log.d(
                tag,
                "Connection state transient: ${_connectionStateFlow.value} -> $rawStatus ($source)"
            )
            return
        }

        pendingStatusJob?.cancel()
        pendingStatusJob = null
        pendingStatusDueAtMs = null
        publishConnectionStatus(publishedStatus, source, now)
    }

    private fun publishConnectionStatus(status: ConnectionStatus, source: String, now: Long) {
        val previous = _connectionStateFlow.value
        if (previous != status) {
            lastStateChangeAtMs = now
            Log.d(tag, "Connection state changed: $previous -> $status ($source)")
        }

        _connectionStateFlow.value = status

        when (status) {
            is ConnectionStatus.Connected -> {
                reconnectAttempts = 0
                failureStreak = 0
                retryJob?.cancel()
                retryJob = null
            }

            is ConnectionStatus.WaitingForNetwork -> {
                startRetryLoop()
            }

            is ConnectionStatus.Connecting,
            is ConnectionStatus.Updating,
            is ConnectionStatus.ConnectingToProxy -> {
                startRetryLoop()
            }
        }
    }

    private fun schedulePendingStatus(dueAtMs: Long?, source: String) {
        if (dueAtMs == null) return
        if (pendingStatusDueAtMs == dueAtMs && pendingStatusJob?.isActive == true) return

        pendingStatusDueAtMs = dueAtMs
        pendingStatusJob?.cancel()
        pendingStatusJob = scope.launch(dispatchers.default) {
            delay((dueAtMs - System.currentTimeMillis()).coerceAtLeast(0L))
            pendingStatusDueAtMs = null
            val now = System.currentTimeMillis()
            val status = statusStabilizer.publishPendingIfDue(now) ?: return@launch
            publishConnectionStatus(status, "delayed:$source", now)
        }
    }

    private fun startRetryLoop() {
        if (retryJob?.isActive == true) return

        retryJob = scope.launch(dispatchers.default) {
            while (isActive) {
                if (_connectionStateFlow.value is ConnectionStatus.Connected) {
                    delay(1_000L)
                    continue
                }

                if (!lastObservedNetworkSnapshot.isUsable) {
                    delay(5_000L)
                    continue
                }

                delay(calculateRetryDelayMs(_connectionStateFlow.value, reconnectAttempts))
                if (!isActive) break

                requestReconnect("scheduled_retry", syncAfter = true)
            }
        }
    }

    private fun startReconnectProcessor() {
        scope.launch(dispatchers.default) {
            while (isActive) {
                val initial = reconnectRequests.receive()
                val batch = mutableListOf(initial)
                delay(RECONNECT_COALESCE_WINDOW_MS)
                while (true) {
                    val next = reconnectRequests.tryReceive().getOrNull() ?: break
                    batch += next
                }

                val merged = batch.reduce { acc, request -> acc.merge(request) }
                performReconnect(merged)
            }
        }
    }

    private fun requestReconnect(reason: String, syncAfter: Boolean = false) {
        reconnectRequests.trySend(
            ReconnectRequest(
                reason = reason,
                syncAfter = syncAfter
            )
        )
    }

    private suspend fun performReconnect(request: ReconnectRequest) {
        reconnectMutex.withLock {
            if (!authorizationReady.value && request.reason != "bootstrap") return

            reconnectAttempts++
            Log.d(
                tag,
                "Reconnect attempt #$reconnectAttempts (${request.reason}), state=${_connectionStateFlow.value}"
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

            val currentState = if (request.syncAfter) {
                syncConnectionStateFromTdlib(request.reason)
            } else {
                null
            }

            if (!lastObservedNetworkSnapshot.isUsable) {
                return
            }

            if (!networkTypeUpdated || currentState !is TdApi.ConnectionStateReady) {
                failureStreak++
            } else {
                failureStreak = 0
            }

            maybeAdjustProxyOnFailures(request.reason)
        }
    }

    private suspend fun syncConnectionStateFromTdlib(reason: String): TdApi.ConnectionState? {
        val state = withTimeoutOrNull(4_000L) {
            withContext(dispatchers.io) {
                chatRemoteSource.getConnectionState()
            }
        }

        if (state == null) {
            if (!lastObservedNetworkSnapshot.isUsable) {
                handleConnectionState(
                    TdApi.ConnectionStateWaitingForNetwork(),
                    "probe:$reason:fallback"
                )
            }
            return null
        }

        handleConnectionState(state, "probe:$reason")
        return state
    }

    private suspend fun maybeAdjustProxyOnFailures(reason: String) {
        val isAutoBestEnabled = appPreferences.isAutoBestProxyEnabled.value
        if (!isAutoBestEnabled) return

        if (failureStreak < FAILURE_THRESHOLD_FOR_PROXY_REAPPLY) return
        if (failureStreak % FAILURE_THRESHOLD_FOR_PROXY_REAPPLY != 0) return

        applyProxyForReason("reconnect_failures:$reason", force = true)
    }

    private fun startProxyManagement() {
        proxyModeWatcherJob?.cancel()
        proxyModeWatcherJob = scope.launch(dispatchers.default) {
            syncEnabledProxyPreferenceFromTdlib("startup_sync")

            proxyApplyMutex.withLock {
                appPreferences.enabledProxyId.value?.let { proxyId ->
                    if (!enableProxy(proxyId, currentEffectiveNetworkType(), "startup_restore")) {
                        appPreferences.setEnabledProxyId(null)
                    }
                }

                applyProxyForReasonLocked(
                    "startup",
                    force = true,
                    networkType = currentEffectiveNetworkType()
                )
            }

            launch {
                appPreferences.proxyNetworkRules.drop(1).collect {
                    applyProxyForReason("rules_changed", force = true)
                }
            }

            launch {
                appPreferences.proxyUnavailableFallback.drop(1).collect {
                    applyProxyForReason("fallback_changed", force = true)
                }
            }

            launch {
                combine(
                    appPreferences.isAutoBestProxyEnabled,
                    appPreferences.proxyAutoCheckIntervalMinutes
                ) { autoBest, intervalMinutes ->
                    autoBest to intervalMinutes
                }.collect { (autoBest, _) ->
                    autoBestJob?.cancel()
                    if (autoBest) {
                        autoBestJob = launchAutoBestLoop()
                    }
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
        }
    }

    private fun launchAutoBestLoop(): Job = scope.launch(dispatchers.default) {
        while (isActive) {
            applyProxyForReason("auto_best_loop", force = true)
            val intervalMinutes = appPreferences.proxyAutoCheckIntervalMinutes.value
                .coerceIn(
                    MIN_SMART_SWITCH_CHECK_INTERVAL_MINUTES,
                    MAX_SMART_SWITCH_CHECK_INTERVAL_MINUTES
                )
            delay(intervalMinutes * 60_000L)
        }
    }

    private suspend fun applyProxyForReason(
        reason: String,
        force: Boolean = false,
        networkType: ProxyNetworkType = currentEffectiveNetworkType()
    ) {
        proxyApplyMutex.withLock {
            applyProxyForReasonLocked(reason, force, networkType)
        }
    }

    private suspend fun applyProxyForReasonLocked(
        reason: String,
        force: Boolean,
        networkType: ProxyNetworkType
    ) {
        if (!force && lastEffectiveNetworkType == networkType) {
            return
        }

        val applied = applyNetworkProxyRuleSafely(reason, networkType)
        if (applied) {
            lastEffectiveNetworkType = networkType
        }
    }

    private suspend fun applyNetworkProxyRuleSafely(
        reason: String,
        networkType: ProxyNetworkType = currentEffectiveNetworkType()
    ): Boolean {
        return coRunCatching {
            applyNetworkProxyRule(reason, networkType)
        }.onFailure { error ->
            if (error.isExpectedProxyFailure()) {
                Log.w(tag, "Proxy rule apply failed ($reason): ${error.message}")
            } else {
                Log.e(tag, "Error applying proxy rule ($reason)", error)
            }
        }.isSuccess
    }

    private suspend fun applyNetworkProxyRule(reason: String, networkType: ProxyNetworkType) {
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
                ) {
                    return
                }
                handleUnavailableFallback(networkType, "$reason:last_used")
            }

            ProxyNetworkMode.SPECIFIC_PROXY -> {
                val target = rule.specificProxyId
                if (target != null && enableProxy(
                        target,
                        networkType,
                        "$reason:specific"
                    )
                ) {
                    return
                }
                handleUnavailableFallback(networkType, "$reason:specific")
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

        val proxyChecks = coroutineScope {
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
        }

        val reachable = proxyChecks.filter { it.second != Long.MAX_VALUE }
        if (reachable.isEmpty()) {
            Log.w(tag, "All proxies are unreachable, switching to direct connection")
            disableProxyIfNeeded("$reason:all_unreachable")
            return false
        }

        val mode = appPreferences.proxySmartSwitchMode.value
        val selected = when (mode) {
            ProxySmartSwitchMode.BEST_PING -> reachable.minByOrNull { it.second }
            ProxySmartSwitchMode.RANDOM_AVAILABLE -> reachable.randomOrNull()
        } ?: return false

        val currentEnabled = proxies.find { it.isEnabled }
        if (selected.first.id != currentEnabled?.id) {
            Log.d(
                tag,
                "Switching proxy (${mode.name}) to ${selected.first.server}:${selected.first.port} (${selected.second}ms) ($reason)"
            )
            return enableProxy(selected.first.id, networkType, "$reason:switch")
        }

        appPreferences.setLastUsedProxyIdForNetwork(networkType, selected.first.id)
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
                if (!lastObservedNetworkSnapshot.isUsable) continue
                if (stuckForMs >= 20_000L) {
                    requestReconnect("watchdog", syncAfter = true)
                }

                if (stuckForMs >= 120_000L) {
                    applyProxyForReason("watchdog_proxy_fallback", force = true)
                }
            }
        }
    }

    private fun currentEffectiveNetworkType(): ProxyNetworkType = lastObservedNetworkSnapshot.type

    private fun TdApi.ConnectionState.toConnectionStatus(): ConnectionStatus = when (this) {
        is TdApi.ConnectionStateReady -> ConnectionStatus.Connected
        is TdApi.ConnectionStateConnecting -> ConnectionStatus.Connecting
        is TdApi.ConnectionStateUpdating -> ConnectionStatus.Updating
        is TdApi.ConnectionStateWaitingForNetwork -> ConnectionStatus.WaitingForNetwork
        is TdApi.ConnectionStateConnectingToProxy -> ConnectionStatus.ConnectingToProxy
        else -> ConnectionStatus.Connecting
    }

    private fun calculateRetryDelayMs(status: ConnectionStatus, attempts: Int): Long {
        val base = when (status) {
            is ConnectionStatus.WaitingForNetwork -> 2_500L
            is ConnectionStatus.ConnectingToProxy -> 3_500L
            is ConnectionStatus.Updating -> 2_000L
            is ConnectionStatus.Connecting -> 1_500L
            is ConnectionStatus.Connected -> 1_000L
        }
        val backoff = (base * (1L shl attempts.coerceAtMost(5))).coerceAtMost(MAX_RETRY_DELAY_MS)
        val jitter = Random.nextLong(200L, 1_200L)
        return backoff + jitter
    }

    private data class ReconnectRequest(
        val reason: String,
        val syncAfter: Boolean
    ) {
        fun merge(other: ReconnectRequest): ReconnectRequest = ReconnectRequest(
            reason = "$reason,${other.reason}",
            syncAfter = syncAfter || other.syncAfter
        )
    }

    companion object {
        private const val MAX_RETRY_DELAY_MS = 60_000L
        private const val RECONNECT_COALESCE_WINDOW_MS = 350L
        private const val FAILURE_THRESHOLD_FOR_PROXY_REAPPLY = 3
    }
}
