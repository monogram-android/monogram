package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.domain.models.ProfilePhotoMedia
import org.monogram.domain.repository.ProfilePhotoRepository

/** Prevents selected MTProto accounts from constructing TDLib-backed media reads. */
internal class TelegramBackendProfilePhotoRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> ProfilePhotoRepository,
    scope: CoroutineScope,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : ProfilePhotoRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)

    init {
        scope.launch {
            selectionStore.observe(accountId).collect { selectedBackend.value = it }
        }
    }

    override suspend fun getUserProfilePhotos(userId: Long, offset: Int, limit: Int): List<ProfilePhotoMedia> =
        when (selected()) {
            TelegramBackendKind.LEGACY -> legacy.getUserProfilePhotos(userId, offset, limit)
            TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
        }

    override suspend fun getChatProfilePhotos(chatId: Long, offset: Int, limit: Int): List<ProfilePhotoMedia> =
        when (selected()) {
            TelegramBackendKind.LEGACY -> legacy.getChatProfilePhotos(chatId, offset, limit)
            TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
        }

    override fun getUserProfilePhotosFlow(userId: Long): Flow<List<ProfilePhotoMedia>> = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getUserProfilePhotosFlow(userId)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    override fun getChatProfilePhotosFlow(chatId: Long): Flow<List<ProfilePhotoMedia>> = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getChatProfilePhotosFlow(chatId)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    private fun selected(): TelegramBackendKind = checkNotNull(selectedBackend.value) {
        "Telegram backend selection is not loaded"
    }

    private fun unsupported(): Nothing = throw UnsupportedOperationException(
        "MTProto profile photo media is not available"
    )

    private companion object { const val DEFAULT_ACCOUNT_ID = "default" }
}
