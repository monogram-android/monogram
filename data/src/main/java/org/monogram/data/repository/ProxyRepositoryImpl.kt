package org.monogram.data.repository

import org.drinkless.tdlib.TdApi
import org.monogram.data.core.coRunCatching
import org.monogram.data.datasource.remote.ProxyRemoteDataSource
import org.monogram.domain.models.Proxy
import org.monogram.domain.models.ProxyInput
import org.monogram.domain.models.toDomainProxy
import org.monogram.domain.models.toProxyTypeModel
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.ProxyRepository

class ProxyRepositoryImpl(
    private val remote: ProxyRemoteDataSource,
    private val appPreferences: AppPreferencesProvider
) : ProxyRepository {

    override suspend fun getProxies(): List<Proxy> = coRunCatching {
        remote.getProxies().map { it.toDomainProxy() }
    }.getOrElse { emptyList() }

    override suspend fun addProxy(input: ProxyInput, enable: Boolean): Proxy? = coRunCatching {
        val proxy = remote.addProxy(
            server = input.server,
            port = input.port,
            enable = enable,
            type = input.type.toProxyTypeModel()
        )
        if (enable) appPreferences.setEnabledProxyId(proxy.id)
        proxy.toDomainProxy()
    }.getOrNull()

    override suspend fun editProxy(proxyId: Int, input: ProxyInput, enable: Boolean): Proxy? =
        coRunCatching {
            val proxy = remote.editProxy(
                proxyId = proxyId,
                server = input.server,
                port = input.port,
                enable = enable,
                type = input.type.toProxyTypeModel()
            )
            if (enable) appPreferences.setEnabledProxyId(proxy.id)
            proxy.toDomainProxy()
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
        if (appPreferences.enabledProxyId.value == proxyId) {
            appPreferences.setEnabledProxyId(null)
        }
        true
    }.getOrDefault(false)

    override suspend fun setDnsType(type: String) {
        remote.setOption("dns_type", TdApi.OptionValueString(type))
    }

    override suspend fun setCustomDnsUrl(url: String) {
        remote.setOption("custom_dns_url", TdApi.OptionValueString(url))
    }

    override suspend fun setCustomDnsHeaders(headers: String) {
        remote.setOption("custom_dns_headers", TdApi.OptionValueString(headers))
    }

    override suspend fun getDnsType(): String = coRunCatching {
        val option = remote.getOption("dns_type")
        (option as? TdApi.OptionValueString)?.value ?: "google"
    }.getOrDefault("google")

    override suspend fun getCustomDnsUrl(): String = coRunCatching {
        val option = remote.getOption("custom_dns_url")
        (option as? TdApi.OptionValueString)?.value ?: ""
    }.getOrDefault("")

    override suspend fun getCustomDnsHeaders(): String = coRunCatching {
        val option = remote.getOption("custom_dns_headers")
        (option as? TdApi.OptionValueString)?.value ?: ""
    }.getOrDefault("")
}
