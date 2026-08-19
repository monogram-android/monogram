package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.domain.models.ChatModel
import org.monogram.domain.repository.ChatSearchRepository
import org.monogram.domain.repository.SearchMessagesResult

class TelegramBackendChatSearchRouterTest {
    @Test
    fun `routes legacy search lazily`() = runBlocking {
        val selection = FakeSelectionStore(TelegramBackendKind.LEGACY)
        var created = false
        val router = TelegramBackendChatSearchRouter(
            selectionStore = selection,
            legacyFactory = {
                created = true
                FakeSearchRepository()
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        router.searchChats("query")

        assertEquals(true, created)
    }

    @Test
    fun `fails closed for MTProto search`() = runBlocking {
        val router = TelegramBackendChatSearchRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy search must not be created") },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertThrows(UnsupportedOperationException::class.java) {
            runBlocking { router.searchChats("query") }
        }
        Unit
    }

    @Test
    fun `rejects calls before backend selection is loaded`() = runBlocking {
        val router = TelegramBackendChatSearchRouter(
            selectionStore = DelayedSelectionStore(),
            legacyFactory = { FakeSearchRepository() },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { router.searchChats("query") }
        }
        Unit
    }

    private class FakeSelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        private val value = MutableStateFlow(initial)

        override suspend fun get(accountId: String) = value.value
        override fun observe(accountId: String): Flow<TelegramBackendKind> = value
        override suspend fun select(accountId: String, backend: TelegramBackendKind) { value.value = backend }
        override suspend fun reset(accountId: String) { value.value = TelegramBackendKind.LEGACY }
    }

    private class DelayedSelectionStore : TelegramBackendSelectionStore {
        override suspend fun get(accountId: String) = TelegramBackendKind.LEGACY
        override fun observe(accountId: String): Flow<TelegramBackendKind> = flow { awaitCancellation() }
        override suspend fun select(accountId: String, backend: TelegramBackendKind) = Unit
        override suspend fun reset(accountId: String) = Unit
    }

    private class FakeSearchRepository : ChatSearchRepository {
        override val searchHistory: Flow<List<ChatModel>> = emptyFlow()
        override suspend fun searchChats(query: String) = emptyList<ChatModel>()
        override suspend fun searchPublicChats(query: String) = emptyList<ChatModel>()
        override suspend fun searchMessages(query: String, offset: String, limit: Int) =
            SearchMessagesResult(emptyList(), "")
        override fun addSearchChatId(chatId: Long) = Unit
        override fun removeSearchChatId(chatId: Long) = Unit
        override fun clearSearchHistory() = Unit
    }
}
