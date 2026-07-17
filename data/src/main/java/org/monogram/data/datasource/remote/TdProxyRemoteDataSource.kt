package org.monogram.data.datasource.remote

import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import org.monogram.core.DispatcherProvider
import org.monogram.data.compat.buildAddProxy
import org.monogram.data.compat.buildEditProxy
import org.monogram.data.core.coRunCatching
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.mapper.toApi
import org.monogram.data.mapper.toDomain
import org.monogram.domain.models.ProxyModel
import org.monogram.domain.models.ProxyTypeModel
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import kotlin.time.TimeSource

class TdProxyRemoteDataSource(
    private val gateway: TelegramGateway,
    private val dispatchers: DispatcherProvider
) : ProxyRemoteDataSource {
    private companion object {
        val PRODUCTION_DC_IDS = intArrayOf(2, 1, 3, 4, 5)
        const val TEST_TIMEOUT_SECONDS = 10.0
        const val DIRECT_CONNECT_TIMEOUT_MS = 10_000
        private val WEB_DC_URLS = mapOf(
            1 to "wss://pluto.web.telegram.org/apiws",
            2 to "wss://venus.web.telegram.org/apiws",
            3 to "wss://aurora.web.telegram.org/apiws",
            4 to "wss://vesta.web.telegram.org/apiws",
            5 to "wss://flora.web.telegram.org/apiws"
        )
    }

    override suspend fun getProxies(): List<ProxyModel> =
        gateway.execute(TdApi.GetProxies()).proxies.map { it.toDomain() }

    override suspend fun addProxy(
        server: String, port: Int, enable: Boolean, comment: String?, type: ProxyTypeModel
    ): ProxyModel = gateway.execute(
        buildAddProxy(buildProxy(server, port, type), enable, comment)
    ).toDomain()

    override suspend fun editProxy(
        proxyId: Int,
        server: String,
        port: Int,
        enable: Boolean,
        comment: String?,
        type: ProxyTypeModel
    ): ProxyModel = gateway.execute(
        buildEditProxy(proxyId, buildProxy(server, port, type), enable, comment)
    ).toDomain()

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
        val result = gateway.execute(TdApi.PingProxy(buildProxy(server, port, type)))
        return (result.seconds * 1000).toLong()
    }

    override suspend fun testProxy(server: String, port: Int, type: ProxyTypeModel): Long {
        var lastError: Throwable? = null

        PRODUCTION_DC_IDS.forEach { dcId ->
            val result = coRunCatching { testProxyAtDc(server, port, type, dcId) }
            if (result.isSuccess) return result.getOrThrow()
            lastError = result.exceptionOrNull()
        }

        val proxy = buildProxy(server, port, type)
        val pingResult = coRunCatching { gateway.execute(TdApi.PingProxy(proxy)) }
        if (pingResult.isSuccess) return (pingResult.getOrThrow().seconds * 1000).toLong()
        throw (lastError ?: pingResult.exceptionOrNull()
        ?: IllegalStateException("Proxy test failed"))
    }

    override suspend fun testProxyAtDc(
        server: String,
        port: Int,
        type: ProxyTypeModel,
        dcId: Int
    ): Long {
        val mark = TimeSource.Monotonic.markNow()
        gateway.execute(
            TdApi.TestProxy(
                buildProxy(server, port, type),
                dcId,
                TEST_TIMEOUT_SECONDS
            )
        )
        return mark.elapsedNow().inWholeMilliseconds
    }

    override suspend fun testDirectDc(dcId: Int): Long = withContext(dispatchers.io) {
        val endpoint = WEB_DC_URLS[dcId] ?: error("Unsupported DC id: $dcId")
        val host = URI(endpoint).host ?: error("Invalid DC endpoint for id: $dcId")

        val mark = TimeSource.Monotonic.markNow()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, 443), DIRECT_CONNECT_TIMEOUT_MS)
        }
        mark.elapsedNow().inWholeMilliseconds
    }

    override suspend fun setOption(key: String, value: TdApi.OptionValue) {
        gateway.execute(TdApi.SetOption(key, value))
    }

    override suspend fun getOption(key: String): TdApi.OptionValue? {
        return coRunCatching {
            gateway.execute(TdApi.GetOption(key))
        }.getOrNull()
    }

    private fun buildProxy(server: String, port: Int, type: ProxyTypeModel): TdApi.Proxy {
        return TdApi.Proxy(server, port, type.toApi())
    }
}
