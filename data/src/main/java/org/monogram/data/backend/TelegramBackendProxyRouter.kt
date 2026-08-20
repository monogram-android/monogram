package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.domain.models.Proxy
import org.monogram.domain.models.ProxyInput
import org.monogram.domain.repository.ProxyRepository

/** Prevents selected MTProto accounts from constructing the TDLib proxy repository. */
internal class TelegramBackendProxyRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> ProxyRepository,
    scope: CoroutineScope,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : ProxyRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)

    init {
        scope.launch {
            selectionStore.observe(accountId).collect { selectedBackend.value = it }
        }
    }

    override suspend fun getProxies() = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getProxies()
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    override suspend fun addProxy(input: ProxyInput, enable: Boolean): Proxy? = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.addProxy(input, enable)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    override suspend fun editProxy(proxyId: Int, input: ProxyInput, enable: Boolean): Proxy? = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.editProxy(proxyId, input, enable)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    override suspend fun enableProxy(proxyId: Int) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.enableProxy(proxyId)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    override suspend fun disableProxy() = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.disableProxy()
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    override suspend fun removeProxy(proxyId: Int) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.removeProxy(proxyId)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    override suspend fun setDnsType(type: String) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.setDnsType(type)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    override suspend fun setCustomDnsUrl(url: String) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.setCustomDnsUrl(url)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    override suspend fun setCustomDnsHeaders(headers: String) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.setCustomDnsHeaders(headers)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    override suspend fun getDnsType() = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getDnsType()
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    override suspend fun getCustomDnsUrl() = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getCustomDnsUrl()
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    override suspend fun getCustomDnsHeaders() = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getCustomDnsHeaders()
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    private fun selected(): TelegramBackendKind = checkNotNull(selectedBackend.value) {
        "Telegram backend selection is not loaded"
    }

    private fun unsupported(): Nothing = throw UnsupportedOperationException(
        "MTProto proxy management is not available"
    )

    private companion object { const val DEFAULT_ACCOUNT_ID = "default" }
}
