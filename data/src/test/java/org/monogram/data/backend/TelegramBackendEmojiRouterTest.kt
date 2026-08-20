package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.RecentEmojiModel
import org.monogram.domain.models.StickerModel
import org.monogram.domain.repository.EmojiRepository

class TelegramBackendEmojiRouterTest {
    @Test
    fun `selected MTProto custom emoji lookup fails closed without creating legacy repository`() = runBlocking {
        val router = TelegramBackendEmojiRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy emoji repository must not be created") },
            mtProtoFactory = { FakeEmojiRepository() },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        val failure = runCatching { router.searchCustomEmojis("heart") }.exceptionOrNull()

        assertTrue(failure is UnsupportedOperationException)
    }

    private class FakeEmojiRepository : EmojiRepository {
        override val recentEmojis: Flow<List<RecentEmojiModel>> = MutableStateFlow(emptyList())
        override suspend fun getDefaultEmojis() = emptyList<String>()
        override suspend fun searchEmojis(query: String) = emptyList<String>()
        override suspend fun searchCustomEmojis(query: String): List<StickerModel> =
            throw UnsupportedOperationException("unsupported")
        override suspend fun addRecentEmoji(recentEmoji: RecentEmojiModel) = Unit
        override suspend fun clearRecentEmojis() = Unit
        override suspend fun getMessageAvailableReactions(chatId: Long, messageId: Long) = emptyList<String>()
    }

    private class FakeSelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        private val state = MutableStateFlow(initial)
        override suspend fun get(accountId: String) = state.value
        override fun observe(accountId: String): Flow<TelegramBackendKind> = state
        override suspend fun select(accountId: String, backend: TelegramBackendKind) { state.value = backend }
        override suspend fun reset(accountId: String) { state.value = TelegramBackendKind.LEGACY }
    }
}
