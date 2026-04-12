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
import org.monogram.domain.models.ProxyModel
import org.monogram.domain.models.ProxyTypeModel
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.ExternalProxyRepository
import org.monogram.domain.repository.ProxyNetworkMode
import org.monogram.domain.repository.ProxyNetworkRule
import org.monogram.domain.repository.ProxyNetworkType
import org.monogram.domain.repository.ProxySortMode
import org.monogram.domain.repository.ProxyTestResult
import org.monogram.domain.repository.ProxyUnavailableFallback
import org.monogram.domain.repository.defaultProxyNetworkMode
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
    fun onTestProxy(server: String, port: Int, type: ProxyTypeModel)
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
        val testPing: Long? = null,
        val testError: String? = null,
        val proxyErrors: Map<Int, String> = emptyMap(),
        val isTesting: Boolean = false,
        val toastMessage: String? = null,
        val showClearOfflineConfirmation: Boolean = false,
        val showRemoveAllConfirmation: Boolean = false
    )
}

class DefaultProxyComponent(
    context: AppComponentContext,
    private val onBack: () -> Unit
) : ProxyComponent, AppComponentContext by context {

    private val appPreferences: AppPreferencesProvider = container.preferences.appPreferences
    private val externalProxyRepository: ExternalProxyRepository = container.repositories.externalProxyRepository

    private val _state = MutableValue(ProxyComponent.State())
    override val state: Value<ProxyComponent.State> = _state
    private val scope = componentScope
    private var restoreAttempted = false
    private var lastToastMessage: String? = null
    private var lastToastAtMs: Long = 0L

    private fun showToastThrottled(message: String, throttleMs: Long = 1500L) {
        val now = System.currentTimeMillis()
        val isDuplicateTooSoon = lastToastMessage == message && (now - lastToastAtMs) < throttleMs
        if (isDuplicateTooSoon) return
        lastToastMessage = message
        lastToastAtMs = now
        _state.update { it.copy(toastMessage = message) }
    }

    init {
        scope.launch {
            refreshProxies(shouldPing = true)
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
        val restoredProxies = restoreUserProxiesIfNeeded()
        val allProxies = externalProxyRepository.getProxies().ifEmpty { restoredProxies }
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
                isLoading = false
            )
        }
        if (shouldPing) {
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

        val existing = externalProxyRepository.getProxies()
        if (existing.isNotEmpty()) {
            restoreAttempted = true
            return emptyList()
        }

        val restored = backups.mapNotNull { parseProxyBackup(it) }.mapNotNull { backup ->
            externalProxyRepository.addProxy(
                server = backup.server,
                port = backup.port,
                enable = false,
                type = backup.type
            )
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
                proxyToEdit = null,
                testPing = null,
                testError = null,
                isTesting = false
            )
        }
    }

    override fun onEditProxyClicked(proxy: ProxyModel) {
        _state.update {
            it.copy(
                proxyToEdit = proxy,
                isAddingProxy = false,
                testPing = null,
                testError = null,
                isTesting = false
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
            val existing = externalProxyRepository.getProxies()
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

                val proxy = externalProxyRepository.addProxy(
                    server = backup.server,
                    port = backup.port,
                    enable = false,
                    type = backup.type
                )

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
            refreshProxies(shouldPing = false)
            _state.update {
                it.copy(toastMessage = "Imported: $added, skipped: $skipped, invalid: $invalid")
            }
        }
    }

    override fun onEnableProxy(proxyId: Int) {
        scope.launch {
            if (externalProxyRepository.enableProxy(proxyId)) {
                ProxyNetworkType.entries.forEach { networkType ->
                    appPreferences.setLastUsedProxyIdForNetwork(networkType, proxyId)
                }
                refreshProxies(shouldPing = false)
                onPingProxy(proxyId)
            }
        }
    }

    override fun onDisableProxy() {
        scope.launch {
            if (externalProxyRepository.disableProxy()) {
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
        val pingResults = coroutineScope {
            allProxies.map { proxy ->
                proxy.id to async {
                    externalProxyRepository.pingProxyDetailed(proxy.id)
                }
            }.associate { (id, job) -> id to job.await() }
        }

        val updatedProxies = _state.value.proxies.map { proxy ->
            when (val result = pingResults[proxy.id]) {
                is ProxyTestResult.Success -> proxy.copy(ping = result.ping)
                is ProxyTestResult.Failure -> proxy.copy(ping = -1L)
                else -> proxy
            }
        }

        _state.update {
            val updatedErrors = it.proxyErrors.toMutableMap()
            pingResults.forEach { (proxyId, result) ->
                if (result is ProxyTestResult.Failure) {
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
                proxyErrors = updatedErrors
            )
        }
    }

    override fun onPingProxy(proxyId: Int) {
        scope.launch {
            val result = externalProxyRepository.pingProxyDetailed(proxyId)
            val ping = if (result is ProxyTestResult.Success) result.ping else -1L
            val errorMessage = (result as? ProxyTestResult.Failure)?.message

            val updatedProxies = _state.value.proxies.map {
                if (it.id == proxyId) it.copy(ping = ping) else it
            }

            _state.update {
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
                    proxyErrors = updatedErrors
                )
            }
        }
    }

    override fun onTestProxy(server: String, port: Int, type: ProxyTypeModel) {
        _state.update { it.copy(isTesting = true, testPing = null, testError = null) }
        scope.launch {
            when (val result = externalProxyRepository.testProxyDetailed(server, port, type)) {
                is ProxyTestResult.Success -> {
                    _state.update {
                        it.copy(
                            isTesting = false,
                            testPing = result.ping,
                            testError = null
                        )
                    }
                }

                is ProxyTestResult.Failure -> {
                    _state.update {
                        it.copy(
                            isTesting = false,
                            testPing = -1L,
                            testError = result.message
                        )
                    }
                }
            }
        }
    }

    override fun onAddProxy(server: String, port: Int, type: ProxyTypeModel) {
        scope.launch {
            val proxy = externalProxyRepository.addProxy(server, port, true, type)
            if (proxy != null) {
                addProxyToBackup(proxy)
                ProxyNetworkType.entries.forEach { networkType ->
                    appPreferences.setLastUsedProxyIdForNetwork(networkType, proxy.id)
                }
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
            val proxy = externalProxyRepository.editProxy(proxyId, server, port, true, type)
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
            if (externalProxyRepository.removeProxy(proxy.id)) {
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
                proxyToEdit = null,
                testPing = null,
                testError = null,
                isTesting = false
            )
        }
    }

    override fun onAutoBestProxyToggled(enabled: Boolean) {
        appPreferences.setAutoBestProxyEnabled(enabled)
    }

    override fun onPreferIpv6Toggled(enabled: Boolean) {
        externalProxyRepository.setPreferIpv6(enabled)
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
            val proxiesToDelete = _state.value.proxies.filter { it.ping == -1L }
            val deletedIds = proxiesToDelete.map { it.id }.toSet()
            proxiesToDelete.forEach { proxy ->
                if (externalProxyRepository.removeProxy(proxy.id)) {
                    removeProxyFromBackup(proxy)
                }
            }
            if (appPreferences.favoriteProxyId.value in deletedIds) {
                appPreferences.setFavoriteProxyId(null)
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
                if (externalProxyRepository.removeProxy(proxy.id)) {
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
}
