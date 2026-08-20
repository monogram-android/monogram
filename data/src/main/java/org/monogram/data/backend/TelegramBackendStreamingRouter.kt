package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.domain.repository.StreamingRepository

internal class TelegramBackendStreamingRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> StreamingRepository,
    scope: CoroutineScope,
    private val accountId: String = "default",
) : StreamingRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)
    init { scope.launch { selectionStore.observe(accountId).collect { selectedBackend.value = it } } }
    override fun getDownloadProgress(fileId: Int): Flow<Float> = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getDownloadProgress(fileId)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }
    private fun selected() = checkNotNull(selectedBackend.value) { "Telegram backend selection is not loaded" }
    private fun unsupported(): Nothing = throw UnsupportedOperationException("MTProto streaming is not available")
}
