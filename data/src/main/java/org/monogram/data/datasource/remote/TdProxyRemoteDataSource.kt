package org.monogram.data.datasource.remote

import org.monogram.data.core.coRunCatching
import org.drinkless.tdlib.TdApi
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.mapper.toApi
import org.monogram.data.mapper.toDomain
import org.monogram.domain.models.ProxyModel
import org.monogram.domain.models.ProxyTypeModel

class TdProxyRemoteDataSource(
    private val gateway: TelegramGateway
) : ProxyRemoteDataSource {
    override suspend fun getProxies(): List<ProxyModel> =
        gateway.execute(TdApi.GetProxies()).proxies.map { it.toDomain() }

    override suspend fun addProxy(
        server: String, port: Int, enable: Boolean, type: ProxyTypeModel
    ): ProxyModel = gateway.execute(TdApi.AddProxy(TdApi.Proxy(server, port, type.toApi()), enable)).toDomain()

    override suspend fun editProxy(
        proxyId: Int, server: String, port: Int, enable: Boolean, type: ProxyTypeModel
    ): ProxyModel = gateway.execute(TdApi.EditProxy(proxyId, TdApi.Proxy(server, port, type.toApi()), enable)).toDomain()

    override suspend fun enableProxy(proxyId: Int) : Boolean {
        val result = coRunCatching { gateway.execute(TdApi.EnableProxy(proxyId)) }
        return result.isSuccess
    }

    override suspend fun disableProxy() {
        gateway.execute(TdApi.DisableProxy())
    }

    override suspend fun removeProxy(proxyId: Int) {
        gateway.execute(TdApi.RemoveProxy(proxyId))
    }

    override suspend fun pingProxy(server: String, port: Int, type: ProxyTypeModel): Long {
        val result = gateway.execute(TdApi.PingProxy(TdApi.Proxy(server, port, type.toApi())))
        return (result.seconds * 1000).toLong()
    }

    override suspend fun testProxy(server: String, port: Int, type: ProxyTypeModel): Long {
        val start = System.currentTimeMillis()
        gateway.execute(TdApi.TestProxy(TdApi.Proxy(server, port, type.toApi()), 0, 10.0))
        return System.currentTimeMillis() - start
    }

    override suspend fun setOption(key: String, value: TdApi.OptionValue) {
        gateway.execute(TdApi.SetOption(key, value))
    }
}
