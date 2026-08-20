package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.domain.models.GifModel
import org.monogram.domain.repository.GifRepository

/** Keeps TDLib/file-backed GIF operations isolated until MTProto media parity exists. */
internal class TelegramBackendGifRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> GifRepository,
    scope: CoroutineScope,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : GifRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)

    init {
        scope.launch {
            selectionStore.observe(accountId).collect { selectedBackend.value = it }
        }
    }

    override fun getGifFile(gif: GifModel): Flow<String?> = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getGifFile(gif)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    override fun getGifThumbnailFile(fileId: Long): Flow<String?> = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getGifThumbnailFile(fileId)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    override suspend fun getSavedGifs(): List<GifModel> = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getSavedGifs()
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    override suspend fun addSavedGif(path: String) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.addSavedGif(path)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    override suspend fun searchGifs(query: String): List<GifModel> = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.searchGifs(query)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    private fun selected(): TelegramBackendKind = checkNotNull(selectedBackend.value) {
        "Telegram backend selection is not loaded"
    }

    private fun unsupported(): Nothing = throw UnsupportedOperationException(
        "MTProto GIF media is not available"
    )

    private companion object { const val DEFAULT_ACCOUNT_ID = "default" }
}
