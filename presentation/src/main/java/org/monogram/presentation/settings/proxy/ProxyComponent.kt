package org.monogram.presentation.settings.proxy

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.monogram.domain.models.ProxyCheckResult
import org.monogram.domain.models.ProxyInput
import org.monogram.domain.models.ProxyModel
import org.monogram.domain.models.ProxyTypeModel
import org.monogram.domain.models.toDomainProxyType
import org.monogram.domain.models.toProxyModel
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.ProxyDiagnosticsRepository
import org.monogram.domain.repository.ProxyNetworkMode
import org.monogram.domain.repository.ProxyNetworkRule
import org.monogram.domain.repository.ProxyNetworkType
import org.monogram.domain.repository.ProxyRepository
import org.monogram.domain.repository.ProxySortMode
import org.monogram.domain.repository.ProxyUnavailableFallback
import org.monogram.domain.repository.defaultProxyNetworkMode
import org.monogram.presentation.core.util.coRunCatching
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext

interface ProxyComponent {
    val state: Value<State>

    fun onBackClicked()
    fun onAddProxyClicked()
    fun onEditProxyClicked(proxy: ProxyModel)
    fun onProxyClicked(proxy: ProxyModel)
    fun onProxyLongClicked(proxy: ProxyModel)
    fun onEnableProxy(proxyId: Int)
    fun onDisableProxy()
    fun onRemoveProxy(proxyId: Int)
    fun onPingAll()
    fun onPingProxy(proxyId: Int)
    fun onPingDatacenters()
    fun onAddProxy(server: String, port: Int, type: ProxyTypeModel)
    fun onEditProxy(proxyId: Int, server: String, port: Int, type: ProxyTypeModel)
    fun onDismissDeleteConfirmation()
    fun onConfirmDelete()
    fun onDismissAddEdit()
    fun onAutoBestProxyToggled(enabled: Boolean)
    fun onPreferIpv6Toggled(enabled: Boolean)
    fun onProxySortModeChanged(mode: ProxySortMode)
    fun onProxyUnavailableFallbackChanged(fallback: ProxyUnavailableFallback)
    fun onHideOfflineProxiesToggled(enabled: Boolean)
    fun onToggleFavoriteProxy(proxyId: Int)
    fun exportProxiesJson(): String
    fun importProxiesJson(json: String)
    fun onProxyNetworkModeChanged(networkType: ProxyNetworkType, mode: ProxyNetworkMode)
    fun onSpecificProxyForNetworkSelected(networkType: ProxyNetworkType, proxyId: Int)
    fun onClearUnavailableProxies()
    fun onRemoveAllProxies()
    fun onConfirmClearUnavailableProxies()
    fun onConfirmRemoveAllProxies()
    fun onDismissToast()
    fun onDismissMassDeleteDialogs()

    data class State(
        val proxies: List<ProxyModel> = emptyList(),
        val visibleProxies: List<ProxyModel> = emptyList(),
        val isLoading: Boolean = false,
        val isAddingProxy: Boolean = false,
        val isAutoBestProxyEnabled: Boolean = false,
        val preferIpv6: Boolean = false,
        val proxySortMode: ProxySortMode = ProxySortMode.LOWEST_PING,
        val proxyUnavailableFallback: ProxyUnavailableFallback = ProxyUnavailableFallback.BEST_PROXY,
        val hideOfflineProxies: Boolean = false,
        val favoriteProxyId: Int? = null,
        val proxyNetworkRules: Map<ProxyNetworkType, ProxyNetworkRule> = ProxyNetworkType.entries.associateWith {
            ProxyNetworkRule(defaultProxyNetworkMode(it))
        },
        val proxyToEdit: ProxyModel? = null,
        val proxyToDelete: ProxyModel? = null,
        val isDcTesting: Boolean = false,
        val dcPingByDcId: Map<Int, Long?> = emptyMap(),
        val dcPingErrorsByDcId: Map<Int, String> = emptyMap(),
        val proxyErrors: Map<Int, String> = emptyMap(),
        val toastMessage: String? = null,
        val showClearOfflineConfirmation: Boolean = false,
        val showRemoveAllConfirmation: Boolean = false,
        val checkingProxyIds: Set<Int> = emptySet()
    )
}

class DefaultProxyComponent(
    context: AppComponentContext,
    private val onBack: () -> Unit
) : ProxyComponent, AppComponentContext by context {
    private companion object {
        val DC_IDS = listOf(2, 1, 3, 4, 5)
    }

    private val appPreferences: AppPreferencesProvider = container.preferences.appPreferences
    private val proxyRepository: ProxyRepository = container.repositories.proxyRepository
    private val proxyDiagnosticsRepository: ProxyDiagnosticsRepository =
        container.repositories.proxyDiagnosticsRepository

    private val _state = MutableValue(ProxyComponent.State())
    override val state: Value<ProxyComponent.State> = _state
    private val scope = componentScope
    private var restoreAttempted = false
    private var lastToastMessage: String? = null
    private var lastToastAtMs: Long = 0L
    private var pingRequestToken = 0L
    private val pingRequestByProxyId = mutableMapOf<Int, Long>()

    private fun showToastThrottled(message: String, throttleMs: Long = 1500L) {
        val now = System.currentTimeMillis()
        val isDuplicateTooSoon = lastToastMessage == message && (now - lastToastAtMs) < throttleMs
        if (isDuplicateTooSoon) return
        lastToastMessage = message
        lastToastAtMs = now
        _state.update { it.copy(toastMessage = message) }
    }

    private fun reservePingRequest(proxyIds: Collection<Int>): Long {
        val token = ++pingRequestToken
        proxyIds.forEach { proxyId -> pingRequestByProxyId[proxyId] = token }
        return token
    }

    private fun isLatestPingRequest(proxyId: Int, token: Long): Boolean {
        return pingRequestByProxyId[proxyId] == token
    }

    init {
        scope.launch {
            coRunCatching { refreshProxies(shouldPing = true) }
                .onFailure {
                    _state.update { state -> state.copy(isLoading = false) }
                    showToastThrottled("Failed to load proxies")
                }
        }

        combine(
            appPreferences.isAutoBestProxyEnabled,
            appPreferences.preferIpv6,
            appPreferences.proxySortMode,
            appPreferences.proxyUnavailableFallback,
            appPreferences.hideOfflineProxies,
        ) { autoBest, ipv6, sortMode, fallback, hideOffline ->
            ProxyPreferencesBaseState(
                autoBest = autoBest,
                preferIpv6 = ipv6,
                sortMode = sortMode,
                fallback = fallback,
                hideOffline = hideOffline
            )
        }
            .combine(appPreferences.favoriteProxyId) { base, favoriteProxyId ->
                base to favoriteProxyId
            }
            .combine(appPreferences.proxyNetworkRules) { baseWithFavorite, networkRules ->
                val (base, favoriteProxyId) = baseWithFavorite
                ProxyPreferencesState(
                    autoBest = base.autoBest,
                    preferIpv6 = base.preferIpv6,
                    sortMode = base.sortMode,
                    fallback = base.fallback,
                    hideOffline = base.hideOffline,
                    favoriteProxyId = favoriteProxyId,
                    networkRules = networkRules
                )
            }
            .distinctUntilChanged()
            .onEach { prefs ->
                _state.update { current ->
                    val visible = buildVisibleProxies(
                        current.proxies,
                        prefs.sortMode,
                        prefs.hideOffline,
                        prefs.favoriteProxyId
                    )
                    current.copy(
                        isAutoBestProxyEnabled = prefs.autoBest,
                        preferIpv6 = prefs.preferIpv6,
                        proxySortMode = prefs.sortMode,
                        proxyUnavailableFallback = prefs.fallback,
                        hideOfflineProxies = prefs.hideOffline,
                        favoriteProxyId = prefs.favoriteProxyId,
                        proxyNetworkRules = prefs.networkRules,
                        visibleProxies = visible
                    )
                }
            }.launchIn(scope)
    }

    private data class ProxyPreferencesBaseState(
        val autoBest: Boolean,
        val preferIpv6: Boolean,
        val sortMode: ProxySortMode,
        val fallback: ProxyUnavailableFallback,
        val hideOffline: Boolean
    )

    private data class ProxyPreferencesState(
        val autoBest: Boolean,
        val preferIpv6: Boolean,
        val sortMode: ProxySortMode,
        val fallback: ProxyUnavailableFallback,
        val hideOffline: Boolean,
        val favoriteProxyId: Int?,
        val networkRules: Map<ProxyNetworkType, ProxyNetworkRule>
    )

    private suspend fun refreshProxies(shouldPing: Boolean = false) {
        _state.update { it.copy(isLoading = true) }
        val restoredProxies = coRunCatching { restoreUserProxiesIfNeeded() }
            .getOrElse { emptyList() }
        val allProxies = coRunCatching { proxyRepository.getProxies().map { it.toProxyModel() } }
            .onFailure { showToastThrottled("Failed to load proxies") }
            .getOrElse { emptyList() }
            .ifEmpty { restoredProxies }
        _state.update {
            val availableIds = allProxies.mapTo(HashSet()) { proxy -> proxy.id }
            it.copy(
                proxies = allProxies,
                visibleProxies = buildVisibleProxies(
                    allProxies,
                    it.proxySortMode,
                    it.hideOfflineProxies,
                    it.favoriteProxyId
                ),
                proxyErrors = it.proxyErrors.filterKeys { id -> id in availableIds },
                checkingProxyIds = it.checkingProxyIds.filter { id -> id in availableIds }.toSet(),
                isLoading = false
            )
        }
        if (shouldPing && allProxies.isNotEmpty()) {
            performPingAll()
        }
    }

    private fun buildVisibleProxies(
        proxies: List<ProxyModel>,
        sortMode: ProxySortMode,
        hideOffline: Boolean,
        favoriteProxyId: Int?
    ): List<ProxyModel> {
        val filtered = if (hideOffline) proxies.filter { it.ping != -1L } else proxies
        return when (sortMode) {
            ProxySortMode.ACTIVE_FIRST -> filtered.sortedWith(
                compareBy<ProxyModel> { topPriorityOrder(it, favoriteProxyId) }
                    .thenBy { it.server.lowercase() }
                    .thenBy { it.port }
            )

            ProxySortMode.LOWEST_PING -> filtered.sortedWith(
                compareBy<ProxyModel> { topPriorityOrder(it, favoriteProxyId) }
                    .thenBy { pingSortValue(it.ping) }
                    .thenBy { it.server.lowercase() }
                    .thenBy { it.port }
            )

            ProxySortMode.SERVER_NAME -> filtered.sortedWith(
                compareBy<ProxyModel> { topPriorityOrder(it, favoriteProxyId) }
                    .thenBy { it.server.lowercase() }
                    .thenBy { it.port }
            )

            ProxySortMode.PROXY_TYPE -> filtered.sortedWith(
                compareBy<ProxyModel> { topPriorityOrder(it, favoriteProxyId) }
                    .thenBy { proxyTypeOrder(it.type) }
                    .thenBy { it.server.lowercase() }
                    .thenBy { it.port }
            )

            ProxySortMode.STATUS -> filtered.sortedWith(
                compareBy<ProxyModel> { topPriorityOrder(it, favoriteProxyId) }
                    .thenBy { statusOrder(it) }
                    .thenBy { pingSortValue(it.ping) }
                    .thenBy { it.server.lowercase() }
            )
        }
    }

    private fun topPriorityOrder(proxy: ProxyModel, favoriteProxyId: Int?): Int {
        return when {
            proxy.isEnabled -> 0
            favoriteProxyId != null && proxy.id == favoriteProxyId -> 1
            else -> 2
        }
    }

    private fun pingSortValue(ping: Long?): Long {
        return if (ping == null || ping < 0L) Long.MAX_VALUE else ping
    }

    private fun proxyTypeOrder(type: ProxyTypeModel): Int {
        return when (type) {
            is ProxyTypeModel.Mtproto -> 0
            is ProxyTypeModel.Socks5 -> 1
            is ProxyTypeModel.Http -> 2
        }
    }

    private fun statusOrder(proxy: ProxyModel): Int {
        val ping = proxy.ping
        return when {
            proxy.isEnabled -> 0
            ping != null && ping >= 0L -> 1
            ping == null -> 2
            else -> 3
        }
    }

    private fun upsertProxyLocally(
        proxy: ProxyModel,
        replaceId: Int? = null,
        closeEditor: Boolean = false
    ) {
        _state.update { current ->
            val existingId = replaceId ?: proxy.id
            val withoutOld = current.proxies.filterNot { it.id == existingId || it.id == proxy.id }
            val updatedProxies = withoutOld + proxy
            val updatedErrors = current.proxyErrors.toMutableMap().apply {
                remove(existingId)
                remove(proxy.id)
            }
            current.copy(
                proxies = updatedProxies,
                visibleProxies = buildVisibleProxies(
                    updatedProxies,
                    current.proxySortMode,
                    current.hideOfflineProxies,
                    current.favoriteProxyId
                ),
                proxyErrors = updatedErrors,
                isAddingProxy = false,
                proxyToEdit = if (closeEditor) null else current.proxyToEdit
            )
        }
    }

    private suspend fun restoreUserProxiesIfNeeded(): List<ProxyModel> {
        if (restoreAttempted) return emptyList()

        val backups = appPreferences.userProxyBackups.value
        if (backups.isEmpty()) {
            restoreAttempted = true
            return emptyList()
        }

        val existing = coRunCatching { proxyRepository.getProxies() }
            .getOrElse { emptyList() }
        if (existing.isNotEmpty()) {
            restoreAttempted = true
            return emptyList()
        }

        val restored = backups.mapNotNull { parseProxyBackup(it) }.mapNotNull { backup ->
            proxyRepository.addProxy(
                input = ProxyInput(
                    server = backup.server,
                    port = backup.port,
                    type = backup.type.toDomainProxyType()
                ),
                enable = false
            )?.toProxyModel()
        }
        restoreAttempted = true
        return restored
    }

    private fun addProxyToBackup(proxy: ProxyModel) {
        val current = appPreferences.userProxyBackups.value.toMutableSet()
        current.add(serializeProxyBackup(proxy))
        appPreferences.setUserProxyBackups(current)
    }

    private fun removeProxyFromBackup(proxy: ProxyModel) {
        val current = appPreferences.userProxyBackups.value.toMutableSet()
        current.remove(serializeProxyBackup(proxy))
        appPreferences.setUserProxyBackups(current)
    }

    private fun replaceProxyInBackup(oldProxy: ProxyModel?, newProxy: ProxyModel) {
        val current = appPreferences.userProxyBackups.value.toMutableSet()
        if (oldProxy != null) {
            current.remove(serializeProxyBackup(oldProxy))
        }
        current.add(serializeProxyBackup(newProxy))
        appPreferences.setUserProxyBackups(current)
    }

    private fun serializeProxyBackup(proxy: ProxyModel): String = JSONObject().apply {
        put("server", proxy.server)
        put("port", proxy.port)
        when (val type = proxy.type) {
            is ProxyTypeModel.Mtproto -> {
                put("type", "mtproto")
                put("secret", type.secret)
            }

            is ProxyTypeModel.Socks5 -> {
                put("type", "socks5")
                put("username", type.username)
                put("password", type.password)
            }

            is ProxyTypeModel.Http -> {
                put("type", "http")
                put("username", type.username)
                put("password", type.password)
                put("httpOnly", type.httpOnly)
            }
        }
    }.toString()

    private fun parseProxyBackup(raw: String): ProxyBackup? {
        return runCatching {
            val json = JSONObject(raw)
            val type = when (json.optString("type")) {
                "mtproto" -> ProxyTypeModel.Mtproto(json.optString("secret"))
                "socks5" -> ProxyTypeModel.Socks5(
                    username = json.optString("username"),
                    password = json.optString("password")
                )

                "http" -> ProxyTypeModel.Http(
                    username = json.optString("username"),
                    password = json.optString("password"),
                    httpOnly = json.optBoolean("httpOnly", false)
                )

                else -> return null
            }

            val server = json.optString("server")
            val port = json.optInt("port", 443)
            if (server.isBlank() || port !in 1..65535) return null
            ProxyBackup(server = server, port = port, type = type)
        }.getOrNull()
    }

    private data class ProxyBackup(
        val server: String,
        val port: Int,
        val type: ProxyTypeModel
    )

    private fun proxyFingerprint(server: String, port: Int, type: ProxyTypeModel): String {
        return when (type) {
            is ProxyTypeModel.Mtproto -> "mtproto|$server|$port|${type.secret}"
            is ProxyTypeModel.Socks5 -> "socks5|$server|$port|${type.username}|${type.password}"
            is ProxyTypeModel.Http -> "http|$server|$port|${type.username}|${type.password}|${type.httpOnly}"
        }
    }

    private fun parseProxyBackupJson(json: JSONObject): ProxyBackup? {
        val type = when (json.optString("type")) {
            "mtproto" -> ProxyTypeModel.Mtproto(json.optString("secret"))
            "socks5" -> ProxyTypeModel.Socks5(
                username = json.optString("username"),
                password = json.optString("password")
            )

            "http" -> ProxyTypeModel.Http(
                username = json.optString("username"),
                password = json.optString("password"),
                httpOnly = json.optBoolean("httpOnly", false)
            )

            else -> return null
        }

        val server = json.optString("server")
        val port = json.optInt("port", 443)
        if (server.isBlank() || port !in 1..65535) return null

        return ProxyBackup(server = server, port = port, type = type)
    }

    private fun proxyToJson(proxy: ProxyModel): JSONObject {
        return JSONObject().apply {
            put("server", proxy.server)
            put("port", proxy.port)
            when (val type = proxy.type) {
                is ProxyTypeModel.Mtproto -> {
                    put("type", "mtproto")
                    put("secret", type.secret)
                }

                is ProxyTypeModel.Socks5 -> {
                    put("type", "socks5")
                    put("username", type.username)
                    put("password", type.password)
                }

                is ProxyTypeModel.Http -> {
                    put("type", "http")
                    put("username", type.username)
                    put("password", type.password)
                    put("httpOnly", type.httpOnly)
                }
            }
            put("favorite", proxy.id == appPreferences.favoriteProxyId.value)
        }
    }

    override fun onBackClicked() = onBack()

    override fun onAddProxyClicked() {
        _state.update {
            it.copy(
                isAddingProxy = true,
                proxyToEdit = null
            )
        }
    }

    override fun onEditProxyClicked(proxy: ProxyModel) {
        _state.update {
            it.copy(
                proxyToEdit = proxy,
                isAddingProxy = false
            )
        }
    }

    override fun onProxyClicked(proxy: ProxyModel) {
        if (proxy.isEnabled) {
            onDisableProxy()
        } else {
            onEnableProxy(proxy.id)
        }
    }

    override fun onProxyLongClicked(proxy: ProxyModel) {
        onEditProxyClicked(proxy)
    }

    override fun onToggleFavoriteProxy(proxyId: Int) {
        val currentFavorite = appPreferences.favoriteProxyId.value
        val nextFavorite = if (currentFavorite == proxyId) null else proxyId
        appPreferences.setFavoriteProxyId(nextFavorite)
    }

    override fun exportProxiesJson(): String {
        val proxiesArray = JSONArray()
        _state.value.proxies.forEach { proxy ->
            proxiesArray.put(proxyToJson(proxy))
        }

        return JSONObject().apply {
            put("version", 1)
            put("proxies", proxiesArray)
        }.toString(2)
    }

    override fun importProxiesJson(json: String) {
        scope.launch {
            val existing = coRunCatching { proxyRepository.getProxies().map { it.toProxyModel() } }
                .onFailure { showToastThrottled("Failed to load existing proxies") }
                .getOrElse { emptyList() }
            val fingerprintToId = existing.associate { proxy ->
                proxyFingerprint(proxy.server, proxy.port, proxy.type) to proxy.id
            }.toMutableMap()

            var added = 0
            var skipped = 0
            var invalid = 0
            var favoriteProxyIdToSet: Int? = null

            val parsedEntries = runCatching {
                val trimmed = json.trim()
                if (trimmed.startsWith("[")) {
                    val array = JSONArray(trimmed)
                    List(array.length()) { index -> array.optJSONObject(index) }
                } else {
                    val root = JSONObject(trimmed)
                    val array = root.optJSONArray("proxies") ?: JSONArray()
                    List(array.length()) { index -> array.optJSONObject(index) }
                }
            }.getOrNull()

            if (parsedEntries == null) {
                _state.update { it.copy(toastMessage = "Import failed: invalid file") }
                return@launch
            }

            parsedEntries.forEach { item ->
                if (item == null) {
                    invalid++
                    return@forEach
                }

                val backup = parseProxyBackupJson(item)
                if (backup == null) {
                    invalid++
                    return@forEach
                }

                val fingerprint = proxyFingerprint(backup.server, backup.port, backup.type)
                val existingId = fingerprintToId[fingerprint]
                if (existingId != null) {
                    skipped++
                    if (item.optBoolean("favorite", false) && favoriteProxyIdToSet == null) {
                        favoriteProxyIdToSet = existingId
                    }
                    return@forEach
                }

                val proxy = proxyRepository.addProxy(
                    input = ProxyInput(
                        server = backup.server,
                        port = backup.port,
                        type = backup.type.toDomainProxyType()
                    ),
                    enable = false
                )?.toProxyModel()

                if (proxy != null) {
                    addProxyToBackup(proxy)
                    fingerprintToId[fingerprint] = proxy.id
                    added++
                    if (item.optBoolean("favorite", false) && favoriteProxyIdToSet == null) {
                        favoriteProxyIdToSet = proxy.id
                    }
                } else {
                    invalid++
                }
            }

            favoriteProxyIdToSet?.let { appPreferences.setFavoriteProxyId(it) }
            refreshProxies(shouldPing = true)
            _state.update {
                it.copy(toastMessage = "Imported: $added, skipped: $skipped, invalid: $invalid")
            }
        }
    }

    override fun onEnableProxy(proxyId: Int) {
        scope.launch {
            if (proxyRepository.enableProxy(proxyId)) {
                ProxyNetworkType.entries.forEach { networkType ->
                    appPreferences.setLastUsedProxyIdForNetwork(networkType, proxyId)
                }
                resetDcDiagnostics()
                refreshProxies(shouldPing = false)
                onPingProxy(proxyId)
            }
        }
    }

    override fun onDisableProxy() {
        scope.launch {
            if (proxyRepository.disableProxy()) {
                resetDcDiagnostics()
                refreshProxies(shouldPing = false)
            }
        }
    }

    override fun onRemoveProxy(proxyId: Int) {
        val proxy = _state.value.proxies.find { it.id == proxyId }
        _state.update { it.copy(proxyToDelete = proxy) }
    }

    override fun onPingAll() {
        scope.launch {
            performPingAll()
        }
    }

    private suspend fun performPingAll() {
        val allProxies = _state.value.proxies
        if (allProxies.isEmpty()) return
        val proxyIds = allProxies.map { it.id }
        val requestToken = reservePingRequest(proxyIds)

        _state.update {
            val updatedProxies = it.proxies.map { proxy ->
                if (proxy.id in proxyIds) proxy.copy(ping = null) else proxy
            }
            it.copy(
                proxies = updatedProxies,
                visibleProxies = buildVisibleProxies(
                    updatedProxies,
                    it.proxySortMode,
                    it.hideOfflineProxies,
                    it.favoriteProxyId
                ),
                checkingProxyIds = it.checkingProxyIds + proxyIds
            )
        }

        val pingResults = coroutineScope {
            allProxies.map { proxy ->
                proxy.id to async {
                    proxyDiagnosticsRepository.pingProxy(proxy.id)
                }
            }.associate { (id, job) -> id to job.await() }
        }

        _state.update {
            val updatedErrors = it.proxyErrors.toMutableMap()
            val finishedIds = mutableSetOf<Int>()
            val updatedProxies = it.proxies.map { proxy ->
                val result = pingResults[proxy.id]
                if (!isLatestPingRequest(proxy.id, requestToken) || result == null) {
                    proxy
                } else {
                    finishedIds += proxy.id
                    when (result) {
                        is ProxyCheckResult.Success -> proxy.copy(ping = result.latencyMs)
                        is ProxyCheckResult.Failure -> proxy.copy(ping = -1L)
                    }
                }
            }
            pingResults.forEach { (proxyId, result) ->
                if (!isLatestPingRequest(proxyId, requestToken)) return@forEach
                if (result is ProxyCheckResult.Failure) {
                    updatedErrors[proxyId] = result.message
                } else {
                    updatedErrors.remove(proxyId)
                }
            }
            it.copy(
                proxies = updatedProxies,
                visibleProxies = buildVisibleProxies(
                    updatedProxies,
                    it.proxySortMode,
                    it.hideOfflineProxies,
                    it.favoriteProxyId
                ),
                proxyErrors = updatedErrors,
                checkingProxyIds = it.checkingProxyIds - finishedIds
            )
        }
    }

    override fun onPingProxy(proxyId: Int) {
        scope.launch {
            val requestToken = reservePingRequest(listOf(proxyId))
            _state.update {
                val updatedProxies = it.proxies.map { proxy ->
                    if (proxy.id == proxyId) proxy.copy(ping = null) else proxy
                }
                it.copy(
                    proxies = updatedProxies,
                    visibleProxies = buildVisibleProxies(
                        updatedProxies,
                        it.proxySortMode,
                        it.hideOfflineProxies,
                        it.favoriteProxyId
                    ),
                    checkingProxyIds = it.checkingProxyIds + proxyId
                )
            }

            val result = proxyDiagnosticsRepository.pingProxy(proxyId)
            val ping = if (result is ProxyCheckResult.Success) result.latencyMs else -1L
            val errorMessage = (result as? ProxyCheckResult.Failure)?.message

            _state.update {
                if (!isLatestPingRequest(proxyId, requestToken)) return@update it
                val updatedProxies = it.proxies.map { proxy ->
                    if (proxy.id == proxyId) proxy.copy(ping = ping) else proxy
                }
                val updatedErrors = it.proxyErrors.toMutableMap()
                if (errorMessage != null) updatedErrors[proxyId] =
                    errorMessage else updatedErrors.remove(proxyId)
                it.copy(
                    proxies = updatedProxies,
                    visibleProxies = buildVisibleProxies(
                        updatedProxies,
                        it.proxySortMode,
                        it.hideOfflineProxies,
                        it.favoriteProxyId
                    ),
                    proxyErrors = updatedErrors,
                    checkingProxyIds = it.checkingProxyIds - proxyId
                )
            }
        }
    }

    override fun onPingDatacenters() {
        scope.launch {
            val activeProxy = _state.value.proxies.firstOrNull { it.isEnabled }
            _state.update {
                it.copy(
                    isDcTesting = true,
                    dcPingByDcId = DC_IDS.associateWith { null },
                    dcPingErrorsByDcId = emptyMap()
                )
            }

            val results = coroutineScope {
                DC_IDS.map { dcId ->
                    dcId to async {
                        if (activeProxy != null) {
                            val input = ProxyInput(
                                server = activeProxy.server,
                                port = activeProxy.port,
                                type = activeProxy.type.toDomainProxyType()
                            )
                            proxyDiagnosticsRepository.testProxyAtDc(input, dcId)
                        } else {
                            proxyDiagnosticsRepository.testDirectDc(dcId)
                        }
                    }
                }.associate { (dcId, job) -> dcId to job.await() }
            }

            _state.update {
                val pings = mutableMapOf<Int, Long?>()
                val errors = mutableMapOf<Int, String>()
                results.forEach { (dcId, result) ->
                    when (result) {
                        is ProxyCheckResult.Success -> pings[dcId] = result.latencyMs
                        is ProxyCheckResult.Failure -> {
                            pings[dcId] = -1L
                            errors[dcId] = result.message
                        }
                    }
                }
                it.copy(
                    isDcTesting = false,
                    dcPingByDcId = pings,
                    dcPingErrorsByDcId = errors
                )
            }
        }
    }

    override fun onAddProxy(server: String, port: Int, type: ProxyTypeModel) {
        scope.launch {
            val proxy = proxyRepository.addProxy(
                input = ProxyInput(server = server, port = port, type = type.toDomainProxyType()),
                enable = true
            )?.toProxyModel()
            if (proxy != null) {
                addProxyToBackup(proxy)
                ProxyNetworkType.entries.forEach { networkType ->
                    appPreferences.setLastUsedProxyIdForNetwork(networkType, proxy.id)
                }
                resetDcDiagnostics()
                upsertProxyLocally(proxy)
                onPingProxy(proxy.id)
            } else {
                showToastThrottled("Failed to add proxy")
            }
        }
    }

    override fun onEditProxy(proxyId: Int, server: String, port: Int, type: ProxyTypeModel) {
        scope.launch {
            val oldProxy = _state.value.proxies.find { it.id == proxyId }
            val proxy = proxyRepository.editProxy(
                proxyId = proxyId,
                input = ProxyInput(server = server, port = port, type = type.toDomainProxyType()),
                enable = true
            )?.toProxyModel()
            if (proxy != null) {
                replaceProxyInBackup(oldProxy, proxy)
                ProxyNetworkType.entries.forEach { networkType ->
                    if (appPreferences.proxyNetworkRules.value[networkType]?.specificProxyId == proxyId) {
                        appPreferences.setSpecificProxyIdForNetwork(networkType, proxy.id)
                    }
                    if (appPreferences.proxyNetworkRules.value[networkType]?.lastUsedProxyId == proxyId) {
                        appPreferences.setLastUsedProxyIdForNetwork(networkType, proxy.id)
                    }
                }
                resetDcDiagnostics()
                upsertProxyLocally(proxy, replaceId = proxyId, closeEditor = true)
                onPingProxy(proxy.id)
            } else {
                showToastThrottled("Failed to save proxy")
            }
        }
    }

    override fun onDismissDeleteConfirmation() {
        _state.update { it.copy(proxyToDelete = null) }
    }

    override fun onConfirmDelete() {
        val proxy = _state.value.proxyToDelete ?: return
        scope.launch {
            if (proxyRepository.removeProxy(proxy.id)) {
                removeProxyFromBackup(proxy)
                if (appPreferences.favoriteProxyId.value == proxy.id) {
                    appPreferences.setFavoriteProxyId(null)
                }
                ProxyNetworkType.entries.forEach { networkType ->
                    val rule = appPreferences.proxyNetworkRules.value[networkType]
                    if (rule?.specificProxyId == proxy.id) {
                        appPreferences.setSpecificProxyIdForNetwork(networkType, null)
                    }
                    if (rule?.lastUsedProxyId == proxy.id) {
                        appPreferences.setLastUsedProxyIdForNetwork(networkType, null)
                    }
                }
                _state.update { it.copy(proxyToDelete = null) }
                refreshProxies(shouldPing = false)
            }
        }
    }

    override fun onDismissAddEdit() {
        _state.update {
            it.copy(
                isAddingProxy = false,
                proxyToEdit = null
            )
        }
    }

    override fun onAutoBestProxyToggled(enabled: Boolean) {
        appPreferences.setAutoBestProxyEnabled(enabled)
    }

    override fun onPreferIpv6Toggled(enabled: Boolean) {
        appPreferences.setPreferIpv6(enabled)
    }

    override fun onProxySortModeChanged(mode: ProxySortMode) {
        appPreferences.setProxySortMode(mode)
    }

    override fun onProxyUnavailableFallbackChanged(fallback: ProxyUnavailableFallback) {
        appPreferences.setProxyUnavailableFallback(fallback)
    }

    override fun onHideOfflineProxiesToggled(enabled: Boolean) {
        appPreferences.setHideOfflineProxies(enabled)
    }

    override fun onProxyNetworkModeChanged(networkType: ProxyNetworkType, mode: ProxyNetworkMode) {
        appPreferences.setProxyNetworkMode(networkType, mode)
    }

    override fun onSpecificProxyForNetworkSelected(networkType: ProxyNetworkType, proxyId: Int) {
        appPreferences.setSpecificProxyIdForNetwork(networkType, proxyId)
        appPreferences.setProxyNetworkMode(networkType, ProxyNetworkMode.SPECIFIC_PROXY)
    }

    override fun onClearUnavailableProxies() {
        _state.update {
            it.copy(
                showClearOfflineConfirmation = true,
                showRemoveAllConfirmation = false
            )
        }
    }

    override fun onConfirmClearUnavailableProxies() {
        scope.launch {
            val checkingIds = _state.value.checkingProxyIds
            val proxiesToDelete = _state.value.proxies.filter {
                (it.ping == -1L || it.ping == null) && it.id !in checkingIds
            }
            val deletedIds = proxiesToDelete.map { it.id }.toSet()
            proxiesToDelete.forEach { proxy ->
                if (proxyRepository.removeProxy(proxy.id)) {
                    removeProxyFromBackup(proxy)
                }
            }
            if (appPreferences.favoriteProxyId.value in deletedIds) {
                appPreferences.setFavoriteProxyId(null)
            }
            ProxyNetworkType.entries.forEach { networkType ->
                val rule = appPreferences.proxyNetworkRules.value[networkType]
                if (rule?.specificProxyId in deletedIds) {
                    appPreferences.setSpecificProxyIdForNetwork(networkType, null)
                }
                if (rule?.lastUsedProxyId in deletedIds) {
                    appPreferences.setLastUsedProxyIdForNetwork(networkType, null)
                }
            }
            _state.update { it.copy(showClearOfflineConfirmation = false) }
            refreshProxies(shouldPing = false)
        }
    }

    override fun onRemoveAllProxies() {
        _state.update {
            it.copy(
                showRemoveAllConfirmation = true,
                showClearOfflineConfirmation = false
            )
        }
    }

    override fun onConfirmRemoveAllProxies() {
        scope.launch {
            _state.value.proxies.forEach { proxy ->
                if (proxyRepository.removeProxy(proxy.id)) {
                    removeProxyFromBackup(proxy)
                }
            }
            appPreferences.setFavoriteProxyId(null)
            ProxyNetworkType.entries.forEach { networkType ->
                appPreferences.setSpecificProxyIdForNetwork(networkType, null)
                appPreferences.setLastUsedProxyIdForNetwork(networkType, null)
            }
            _state.update { it.copy(showRemoveAllConfirmation = false) }
            refreshProxies(shouldPing = false)
        }
    }

    override fun onDismissToast() {
        _state.update { it.copy(toastMessage = null) }
    }

    override fun onDismissMassDeleteDialogs() {
        _state.update {
            it.copy(
                showClearOfflineConfirmation = false,
                showRemoveAllConfirmation = false
            )
        }
    }

    private fun resetDcDiagnostics() {
        _state.update {
            it.copy(
                isDcTesting = false,
                dcPingByDcId = emptyMap(),
                dcPingErrorsByDcId = emptyMap()
            )
        }
    }

}
