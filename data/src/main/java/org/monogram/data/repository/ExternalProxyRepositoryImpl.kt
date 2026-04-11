package org.monogram.data.repository

import kotlinx.coroutines.*
import org.monogram.data.core.coRunCatching
import org.monogram.data.datasource.remote.ProxyRemoteDataSource
import org.monogram.domain.models.ProxyModel
import org.monogram.domain.models.ProxyTypeModel
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.ExternalProxyRepository

class ExternalProxyRepositoryImpl(
    private val remote: ProxyRemoteDataSource,
    private val appPreferences: AppPreferencesProvider
) : ExternalProxyRepository {

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

}