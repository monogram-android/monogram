package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramBackendStoryRouterTest {
    @Test
    fun `story state is available before backend selection loads`() {
        val router = TelegramBackendStoryRouter(
            selectionStore = UnloadedSelectionStore(),
            legacyFactory = { error("legacy story repository must not be created") },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(false, router.stealthMode.value.isActive)
        assertTrue(router.activeStories.value.isEmpty())
    }

    @Test
    fun `selected MTProto stories fail closed without creating legacy repository`() = runBlocking {
        val router = TelegramBackendStoryRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy story repository must not be created") },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        val failure = runCatching { router.getStory(chatId = 1L, storyId = 2) }.exceptionOrNull()

        assertTrue(failure is UnsupportedOperationException)
    }

    private class UnloadedSelectionStore : TelegramBackendSelectionStore {
        private val events = MutableSharedFlow<TelegramBackendKind>()
        override suspend fun get(accountId: String) = TelegramBackendKind.KOTLIN_MTPROTO
        override fun observe(accountId: String): Flow<TelegramBackendKind> = events
        override suspend fun select(accountId: String, backend: TelegramBackendKind) = Unit
        override suspend fun reset(accountId: String) = Unit
    }

    private class FakeSelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        private val state = MutableStateFlow(initial)
        override suspend fun get(accountId: String) = state.value
        override fun observe(accountId: String): Flow<TelegramBackendKind> = state
        override suspend fun select(accountId: String, backend: TelegramBackendKind) { state.value = backend }
        override suspend fun reset(accountId: String) { state.value = TelegramBackendKind.LEGACY }
    }
}
