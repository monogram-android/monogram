package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramBackendLinkHandlerRouterTest {
    @Test
    fun `selected MTProto link operations fail closed without creating legacy handler`() = runBlocking {
        val router = TelegramBackendLinkHandlerRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy link handler must not be created") },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        val failure = runCatching { router.handleLink("https://t.me/example") }.exceptionOrNull()

        assertTrue(failure is UnsupportedOperationException)
    }

    private class FakeSelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        private val state = MutableStateFlow(initial)
        override suspend fun get(accountId: String) = state.value
        override fun observe(accountId: String): Flow<TelegramBackendKind> = state
        override suspend fun select(accountId: String, backend: TelegramBackendKind) { state.value = backend }
        override suspend fun reset(accountId: String) { state.value = TelegramBackendKind.LEGACY }
    }
}
