package org.monogram.data.repository

import kotlinx.coroutines.withTimeoutOrNull
import org.monogram.data.core.coRunCatching
import org.monogram.data.datasource.remote.ProxyRemoteDataSource
import org.monogram.data.gateway.toProxyFailureMessage
import org.monogram.domain.models.ProxyModel
import org.monogram.domain.models.ProxyTypeModel
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.ExternalProxyRepository
import org.monogram.domain.repository.ProxyTestResult

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
        when (val result = pingProxyDetailed(proxyId)) {
            is ProxyTestResult.Success -> result.ping
            is ProxyTestResult.Failure -> null
        }
    }

    override suspend fun pingProxyDetailed(proxyId: Int): ProxyTestResult =
        withTimeoutOrNull(10_000L) {
        coRunCatching {
            val proxy = remote.getProxies().find { it.id == proxyId } ?: return@coRunCatching null
            remote.pingProxy(proxy.server, proxy.port, proxy.type)
        }.fold(
            onSuccess = { ping ->
                if (ping == null) ProxyTestResult.Failure("Proxy is unreachable")
                else ProxyTestResult.Success(ping)
            },
            onFailure = {
                ProxyTestResult.Failure(it.toProxyFailureMessage() ?: "Proxy is unreachable")
            }
        )
        } ?: ProxyTestResult.Failure("Proxy is unreachable")

    override suspend fun testProxy(server: String, port: Int, type: ProxyTypeModel): Long? =
        when (val result = testProxyDetailed(server, port, type)) {
            is ProxyTestResult.Success -> result.ping
            is ProxyTestResult.Failure -> null
        }

    override suspend fun testProxyDetailed(
        server: String,
        port: Int,
        type: ProxyTypeModel
    ): ProxyTestResult =
        withTimeoutOrNull(10_000L) {
            coRunCatching { remote.testProxy(server, port, type) }
                .fold(
                    onSuccess = { ProxyTestResult.Success(it) },
                    onFailure = {
                        ProxyTestResult.Failure(
                            it.toProxyFailureMessage() ?: "Proxy is unreachable"
                        )
                    }
                )
        } ?: ProxyTestResult.Failure("Proxy is unreachable")

    override fun setPreferIpv6(enabled: Boolean) {
        appPreferences.setPreferIpv6(enabled)
    }

}