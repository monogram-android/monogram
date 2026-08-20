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
    private val mtProtoFactory: () -> GifRepository = { error("MTProto GIF repository is not configured") },
    scope: CoroutineScope,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : GifRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)
    private val mtProto by lazy(LazyThreadSafetyMode.NONE, mtProtoFactory)

    init {
        scope.launch {
            selectionStore.observe(accountId).collect { selectedBackend.value = it }
        }
    }

    override fun getGifFile(gif: GifModel): Flow<String?> = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getGifFile(gif)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.getGifFile(gif)
    }

    override fun getGifThumbnailFile(fileId: Long): Flow<String?> = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getGifThumbnailFile(fileId)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.getGifThumbnailFile(fileId)
    }

    override suspend fun getSavedGifs(): List<GifModel> = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getSavedGifs()
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.getSavedGifs()
    }

    override suspend fun addSavedGif(path: String) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.addSavedGif(path)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.addSavedGif(path)
    }

    override suspend fun searchGifs(query: String): List<GifModel> = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.searchGifs(query)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.searchGifs(query)
    }

    private fun selected(): TelegramBackendKind = checkNotNull(selectedBackend.value) {
        "Telegram backend selection is not loaded"
    }

    private companion object { const val DEFAULT_ACCOUNT_ID = "default" }
}
