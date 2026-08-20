package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramBackendChatContractsRouterTest {
    @Test
    fun `selected MTProto forum settings and creation contracts fail closed`() = runBlocking {
        val selection = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val forum = TelegramBackendForumTopicsRouter(selection, { error("legacy forum repository created") }, scope)
        val settings = TelegramBackendChatSettingsRouter(selection, { error("legacy settings repository created") }, scope)
        val creation = TelegramBackendChatCreationRouter(selection, { error("legacy creation repository created") }, scope)

        assertTrue(runCatching { forum.getForumTopics(1L) }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching { settings.setChatTitle(1L, "title") }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching { creation.createChannel("title", "description") }.exceptionOrNull() is UnsupportedOperationException)
    }

    private class FakeSelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        private val state = MutableStateFlow(initial)
        override suspend fun get(accountId: String) = state.value
        override fun observe(accountId: String): Flow<TelegramBackendKind> = state
        override suspend fun select(accountId: String, backend: TelegramBackendKind) { state.value = backend }
        override suspend fun reset(accountId: String) { state.value = TelegramBackendKind.LEGACY }
    }
}
