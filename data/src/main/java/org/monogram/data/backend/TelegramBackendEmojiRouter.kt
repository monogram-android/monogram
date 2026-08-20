package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.domain.models.RecentEmojiModel
import org.monogram.domain.models.StickerModel
import org.monogram.domain.repository.EmojiRepository

internal class TelegramBackendEmojiRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> EmojiRepository,
    private val mtProtoFactory: () -> EmojiRepository,
    scope: CoroutineScope,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : EmojiRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)
    private val mtProto by lazy(LazyThreadSafetyMode.NONE, mtProtoFactory)

    init {
        scope.launch {
            selectionStore.observe(accountId).collect { selectedBackend.value = it }
        }
    }

    override val recentEmojis: Flow<List<RecentEmojiModel>>
        get() = selected().recentEmojis

    override suspend fun getDefaultEmojis() = selected().getDefaultEmojis()
    override suspend fun searchEmojis(query: String) = selected().searchEmojis(query)
    override suspend fun searchCustomEmojis(query: String) = selected().searchCustomEmojis(query)
    override suspend fun addRecentEmoji(recentEmoji: RecentEmojiModel) = selected().addRecentEmoji(recentEmoji)
    override suspend fun clearRecentEmojis() = selected().clearRecentEmojis()
    override suspend fun getMessageAvailableReactions(chatId: Long, messageId: Long) =
        selected().getMessageAvailableReactions(chatId, messageId)

    private fun selected(): EmojiRepository = when (selectedBackend.value) {
        TelegramBackendKind.LEGACY -> legacy
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto
        null -> error("Telegram backend selection is not loaded")
    }

    private companion object { const val DEFAULT_ACCOUNT_ID = "default" }
}
