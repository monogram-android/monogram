package org.monogram.data.repository

import org.monogram.data.core.coRunCatching
import org.monogram.domain.models.ProxyModel
import org.monogram.domain.models.ProxyTypeModel
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.ExternalProxyRepository
import kotlinx.coroutines.*
import androidx.core.net.toUri
import org.monogram.core.DispatcherProvider
import org.monogram.data.datasource.remote.ExternalProxyDataSource
import org.monogram.data.datasource.remote.ProxyRemoteDataSource

class ExternalProxyRepositoryImpl(
    private val remote: ProxyRemoteDataSource,
    private val externalSource: ExternalProxyDataSource,
    private val appPreferences: AppPreferencesProvider,
    private val dispatchers: DispatcherProvider
) : ExternalProxyRepository {

    override suspend fun fetchExternalProxies(): List<ProxyModel> = withContext(dispatchers.io) {
        if (!appPreferences.isTelegaProxyEnabled.value) return@withContext emptyList()

        val urls = externalSource.fetchProxyUrls().distinct()
        if (urls.isEmpty()) return@withContext emptyList()

        val parsed = urls.mapNotNull { url ->
            parseProxyUrl(url)?.let { (server, port, secret) ->
                Triple(url, server to port, ProxyTypeModel.Mtproto(secret))
            }
        }

        val oldIdentifiers = appPreferences.telegaProxyUrls.value
            .mapNotNull { parseProxyUrl(it)?.let { (s, p, _) -> "$s:$p" } }
            .toSet()

        val newIdentifiers = parsed.map { (_, sp, _) -> "${sp.first}:${sp.second}" }.toSet()
        appPreferences.setTelegaProxyUrls(parsed.map { it.first }.toSet())

        val added = parsed.mapNotNull { (_, sp, type) ->
            coRunCatching { remote.addProxy(sp.first, sp.second, false, type) }.getOrNull()
        }

        remote.getProxies().forEach { proxy ->
            val iden = "${proxy.server}:${proxy.port}"
            if (iden in oldIdentifiers && iden !in newIdentifiers && !proxy.isEnabled) {
                coRunCatching { remote.removeProxy(proxy.id) }
            }
        }

        added
    }

    override suspend fun getProxies(): List<ProxyModel> = remote.getProxies()

    override suspend fun addProxy(
        server: String, port: Int, enable: Boolean, type: ProxyTypeModel
    ): ProxyModel? = coRunCatching {
        val proxy = remote.addProxy(server, port, enable, type)
        if (enable) appPreferences.setEnabledProxyId(proxy.id)
        proxy
    }.getOrNull()

    override suspend fun editProxy(
        proxyId: Int, server: String, port: Int, enable: Boolean, type: ProxyTypeModel
    ): ProxyModel? = coRunCatching {
        val proxy = remote.editProxy(proxyId, server, port, enable, type)
        if (enable) appPreferences.setEnabledProxyId(proxy.id)
        proxy
    }.getOrNull()

    override suspend fun enableProxy(proxyId: Int): Boolean = coRunCatching {
        remote.enableProxy(proxyId)
        appPreferences.setEnabledProxyId(proxyId)
        true
    }.getOrDefault(false)

    override suspend fun disableProxy(): Boolean = coRunCatching {
        remote.disableProxy()
        appPreferences.setEnabledProxyId(null)
        true
    }.getOrDefault(false)

    override suspend fun removeProxy(proxyId: Int): Boolean = coRunCatching {
        remote.removeProxy(proxyId)
        if (appPreferences.enabledProxyId.value == proxyId) appPreferences.setEnabledProxyId(null)
        true
    }.getOrDefault(false)

    override suspend fun pingProxy(proxyId: Int): Long? = withTimeoutOrNull(10_000L) {
        coRunCatching {
            val proxy = remote.getProxies().find { it.id == proxyId } ?: return@withTimeoutOrNull null
            remote.pingProxy(proxy.server, proxy.port, proxy.type)
        }.getOrNull()
    }

    override suspend fun testProxy(server: String, port: Int, type: ProxyTypeModel): Long? =
        withTimeoutOrNull(10_000L) {
            coRunCatching { remote.testProxy(server, port, type) }.getOrNull()
        }

    override fun setPreferIpv6(enabled: Boolean) {
        appPreferences.setPreferIpv6(enabled)
    }

    private fun parseProxyUrl(url: String): Triple<String, Int, String>? =
        coRunCatching {
            val uri = url.toUri()
            val server = uri.getQueryParameter("server") ?: return null
            val port = uri.getQueryParameter("port")?.toIntOrNull() ?: 443
            val secret = uri.getQueryParameter("secret") ?: ""
            Triple(server, port, secret)
        }.getOrNull()
}