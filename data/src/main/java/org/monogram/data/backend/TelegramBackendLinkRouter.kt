package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.monogram.core.telegram.TelegramLinkDomains
import org.monogram.data.repository.buildTelegramUrl
import org.monogram.domain.repository.TelegramLinkRepository

/** Keeps link construction available without reading TDLib options for MTProto accounts. */
internal class TelegramBackendLinkRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> TelegramLinkRepository,
    scope: CoroutineScope,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : TelegramLinkRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)
    private val mtProtoBaseUrl = MutableStateFlow(TelegramLinkDomains.DEFAULT_BASE_URL)

    init {
        scope.launch {
            selectionStore.observe(accountId).collect { selectedBackend.value = it }
        }
    }

    override val baseUrl: StateFlow<String>
        get() = when (selected()) {
            TelegramBackendKind.LEGACY -> legacy.baseUrl
            TelegramBackendKind.KOTLIN_MTPROTO -> mtProtoBaseUrl
        }

    override suspend fun buildUrl(path: String): String = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.buildUrl(path)
        TelegramBackendKind.KOTLIN_MTPROTO -> buildTelegramUrl(mtProtoBaseUrl.value, path)
    }

    private fun selected(): TelegramBackendKind = checkNotNull(selectedBackend.value) {
        "Telegram backend selection is not loaded"
    }

    private companion object { const val DEFAULT_ACCOUNT_ID = "default" }
}
