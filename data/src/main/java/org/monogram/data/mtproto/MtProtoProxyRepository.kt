package org.monogram.data.mtproto

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.monogram.data.db.dao.KeyValueDao
import org.monogram.data.db.model.KeyValueEntity
import org.monogram.domain.models.Proxy
import org.monogram.domain.models.ProxyInput
import org.monogram.domain.models.ProxyType
import org.monogram.domain.repository.ProxyRepository

/**
 * Persists the MTProto client's proxy preferences independently of a transport implementation.
 * The selected proxy is retained for the forthcoming proxy-aware transport instead of being sent
 * to Telegram as a Telegram client option.
 */
internal class MtProtoProxyRepository(
    private val keyValues: KeyValueDao,
) : ProxyRepository {
    override suspend fun getProxies(): List<Proxy> = state().proxies.map(StoredProxy::toDomain)

    override suspend fun addProxy(input: ProxyInput, enable: Boolean): Proxy {
        val current = state()
        val id = (current.proxies.maxOfOrNull(StoredProxy::id) ?: 0) + 1
        val proxy = StoredProxy.fromInput(id, input, enable)
        persist(
            current.copy(
                proxies = current.proxies.map { it.copy(isEnabled = false) } + proxy,
            ),
        )
        return proxy.toDomain()
    }

    override suspend fun editProxy(proxyId: Int, input: ProxyInput, enable: Boolean): Proxy? {
        val current = state()
        val existing = current.proxies.firstOrNull { it.id == proxyId } ?: return null
        val updated = StoredProxy.fromInput(proxyId, input, enable).copy(lastUsedDate = existing.lastUsedDate)
        persist(
            current.copy(
                proxies = current.proxies.map {
                    when {
                        it.id == proxyId -> updated
                        enable -> it.copy(isEnabled = false)
                        else -> it
                    }
                },
            ),
        )
        return updated.toDomain()
    }

    override suspend fun enableProxy(proxyId: Int): Boolean {
        val current = state()
        if (current.proxies.none { it.id == proxyId }) return false
        persist(current.copy(proxies = current.proxies.map { it.copy(isEnabled = it.id == proxyId) }))
        return true
    }

    override suspend fun disableProxy(): Boolean {
        val current = state()
        if (current.proxies.none(StoredProxy::isEnabled)) return false
        persist(current.copy(proxies = current.proxies.map { it.copy(isEnabled = false) }))
        return true
    }

    override suspend fun removeProxy(proxyId: Int): Boolean {
        val current = state()
        val remaining = current.proxies.filterNot { it.id == proxyId }
        if (remaining.size == current.proxies.size) return false
        persist(current.copy(proxies = remaining))
        return true
    }

    override suspend fun setDnsType(type: String) = persist(state().copy(dnsType = type.trim().ifBlank { DEFAULT_DNS_TYPE }))

    override suspend fun setCustomDnsUrl(url: String) = persist(state().copy(customDnsUrl = url.trim()))

    override suspend fun setCustomDnsHeaders(headers: String) = persist(state().copy(customDnsHeaders = headers.trim()))

    override suspend fun getDnsType(): String = state().dnsType

    override suspend fun getCustomDnsUrl(): String = state().customDnsUrl

    override suspend fun getCustomDnsHeaders(): String = state().customDnsHeaders

    private suspend fun state(): PersistedState = keyValues.getValue(KEY)?.value
        ?.let { encoded -> runCatching { JSON.decodeFromString<PersistedState>(encoded) }.getOrNull() }
        ?: PersistedState()

    private suspend fun persist(value: PersistedState) {
        keyValues.insertValue(KeyValueEntity(KEY, JSON.encodeToString(PersistedState.serializer(), value)))
    }

    @Serializable
    private data class PersistedState(
        val proxies: List<StoredProxy> = emptyList(),
        val dnsType: String = DEFAULT_DNS_TYPE,
        val customDnsUrl: String = "",
        val customDnsHeaders: String = "",
    )

    @Serializable
    private data class StoredProxy(
        val id: Int,
        val server: String,
        val port: Int,
        val comment: String? = null,
        val isEnabled: Boolean,
        val lastUsedDate: Int,
        val type: String,
        val username: String = "",
        val password: String = "",
        val secret: String = "",
        val httpOnly: Boolean = false,
    ) {
        fun toDomain(): Proxy = Proxy(
            id = id,
            server = server,
            port = port,
            lastUsedDate = lastUsedDate,
            isEnabled = isEnabled,
            comment = comment,
            type = when (type) {
                TYPE_SOCKS5 -> ProxyType.Socks5(username, password)
                TYPE_HTTP -> ProxyType.Http(username, password, httpOnly)
                TYPE_MTPROTO -> ProxyType.Mtproto(secret)
                else -> error("Unknown persisted MTProto proxy type: $type")
            },
        )

        companion object {
            fun fromInput(id: Int, input: ProxyInput, enabled: Boolean): StoredProxy {
                require(input.server.isNotBlank()) { "Proxy server must not be blank" }
                require(input.port in 1..65535) { "Proxy port must be between 1 and 65535" }
                val lastUsedDate = Instant.now().epochSecond.toInt()
                return when (val type = input.type) {
                    is ProxyType.Socks5 -> StoredProxy(id, input.server.trim(), input.port, input.comment, enabled, lastUsedDate, TYPE_SOCKS5, type.username, type.password)
                    is ProxyType.Http -> StoredProxy(id, input.server.trim(), input.port, input.comment, enabled, lastUsedDate, TYPE_HTTP, type.username, type.password, httpOnly = type.httpOnly)
                    is ProxyType.Mtproto -> StoredProxy(id, input.server.trim(), input.port, input.comment, enabled, lastUsedDate, TYPE_MTPROTO, secret = type.secret)
                }
            }
        }
    }

    private companion object {
        const val KEY = "mtproto_proxy_settings_v1"
        const val DEFAULT_DNS_TYPE = "system"
        const val TYPE_SOCKS5 = "socks5"
        const val TYPE_HTTP = "http"
        const val TYPE_MTPROTO = "mtproto"
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
