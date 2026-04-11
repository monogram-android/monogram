package org.monogram.presentation.settings.proxy

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.json.JSONObject
import org.monogram.domain.models.ProxyModel
import org.monogram.domain.models.ProxyTypeModel
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.ExternalProxyRepository
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
    fun onClearUnavailableProxies()
    fun onRemoveAllProxies()
    fun onConfirmClearUnavailableProxies()
    fun onConfirmRemoveAllProxies()
    fun onDismissToast()
    fun onDismissMassDeleteDialogs()

    data class State(
        val proxies: List<ProxyModel> = emptyList(),
        val isLoading: Boolean = false,
        val isAddingProxy: Boolean = false,
        val isAutoBestProxyEnabled: Boolean = false,
        val preferIpv6: Boolean = false,
        val proxyToEdit: ProxyModel? = null,
        val proxyToDelete: ProxyModel? = null,
        val testPing: Long? = null,
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

    init {
        scope.launch {
            refreshProxies(shouldPing = true)
        }

        combine(
            appPreferences.isAutoBestProxyEnabled,
            appPreferences.preferIpv6
        ) { autoBest, ipv6 -> autoBest to ipv6 }
            .distinctUntilChanged()
            .onEach { (autoBest, ipv6) ->
                _state.update {
                    it.copy(
                        isAutoBestProxyEnabled = autoBest,
                        preferIpv6 = ipv6
                    )
                }
            }.launchIn(scope)
    }

    private suspend fun refreshProxies(shouldPing: Boolean = false) {
        _state.update { it.copy(isLoading = true) }
        restoreUserProxiesIfNeeded()
        val allProxies = externalProxyRepository.getProxies()
        _state.update {
            it.copy(
                proxies = allProxies,
                isLoading = false
            )
        }
        if (shouldPing) {
            performPingAll()
        }
    }

    private suspend fun restoreUserProxiesIfNeeded() {
        if (restoreAttempted) return
        restoreAttempted = true

        val backups = appPreferences.userProxyBackups.value
        if (backups.isEmpty()) return

        val existing = externalProxyRepository.getProxies()
        if (existing.isNotEmpty()) return

        backups.mapNotNull { parseProxyBackup(it) }.forEach { backup ->
            externalProxyRepository.addProxy(
                server = backup.server,
                port = backup.port,
                enable = false,
                type = backup.type
            )
        }
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

    override fun onBackClicked() = onBack()

    override fun onAddProxyClicked() {
        _state.update { it.copy(isAddingProxy = true, proxyToEdit = null, testPing = null, isTesting = false) }
    }

    override fun onEditProxyClicked(proxy: ProxyModel) {
        _state.update { it.copy(proxyToEdit = proxy, isAddingProxy = false, testPing = null, isTesting = false) }
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

    override fun onEnableProxy(proxyId: Int) {
        scope.launch {
            if (externalProxyRepository.enableProxy(proxyId)) {
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
        val pings = coroutineScope {
            allProxies.map { proxy ->
                proxy.id to async {
                    withTimeoutOrNull(5000) {
                        externalProxyRepository.pingProxy(proxy.id)
                    } ?: -1L
                }
            }.associate { (id, job) -> id to job.await() }
        }

        val updatedRegular = _state.value.proxies.map { proxy ->
            pings[proxy.id]?.let { proxy.copy(ping = it) } ?: proxy
        }

        _state.update { it.copy(proxies = updatedRegular) }
    }

    override fun onPingProxy(proxyId: Int) {
        scope.launch {
            val ping = withTimeoutOrNull(5000) {
                externalProxyRepository.pingProxy(proxyId)
            } ?: -1L

            val updatedRegular = _state.value.proxies.map {
                if (it.id == proxyId) it.copy(ping = ping) else it
            }

            _state.update { it.copy(proxies = updatedRegular) }
        }
    }

    override fun onTestProxy(server: String, port: Int, type: ProxyTypeModel) {
        _state.update { it.copy(isTesting = true, testPing = null) }
        scope.launch {
            val ping = withTimeoutOrNull(10000) {
                externalProxyRepository.testProxy(server, port, type)
            } ?: -1L
            _state.update { it.copy(isTesting = false, testPing = ping) }
        }
    }

    override fun onAddProxy(server: String, port: Int, type: ProxyTypeModel) {
        scope.launch {
            val proxy = externalProxyRepository.addProxy(server, port, true, type)
            if (proxy != null) {
                addProxyToBackup(proxy)
                _state.update { it.copy(isAddingProxy = false) }
                refreshProxies(shouldPing = false)
                onPingProxy(proxy.id)
            }
        }
    }

    override fun onEditProxy(proxyId: Int, server: String, port: Int, type: ProxyTypeModel) {
        scope.launch {
            val oldProxy = _state.value.proxies.find { it.id == proxyId }
            val proxy = externalProxyRepository.editProxy(proxyId, server, port, true, type)
            if (proxy != null) {
                replaceProxyInBackup(oldProxy, proxy)
                _state.update { it.copy(proxyToEdit = null) }
                refreshProxies(shouldPing = false)
                onPingProxy(proxy.id)
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
                _state.update { it.copy(proxyToDelete = null) }
                refreshProxies(shouldPing = false)
            }
        }
    }

    override fun onDismissAddEdit() {
        _state.update { it.copy(isAddingProxy = false, proxyToEdit = null, testPing = null, isTesting = false) }
    }

    override fun onAutoBestProxyToggled(enabled: Boolean) {
        appPreferences.setAutoBestProxyEnabled(enabled)
    }

    override fun onPreferIpv6Toggled(enabled: Boolean) {
        externalProxyRepository.setPreferIpv6(enabled)
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
            proxiesToDelete.forEach { proxy ->
                if (externalProxyRepository.removeProxy(proxy.id)) {
                    removeProxyFromBackup(proxy)
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
                if (externalProxyRepository.removeProxy(proxy.id)) {
                    removeProxyFromBackup(proxy)
                }
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
