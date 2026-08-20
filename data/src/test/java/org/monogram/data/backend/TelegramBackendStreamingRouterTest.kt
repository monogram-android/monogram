package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramBackendStreamingRouterTest {
    @Test
    fun `selected MTProto streaming fails closed without creating legacy repository`() = runBlocking {
        val router = TelegramBackendStreamingRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy streaming repository must not be created") },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        val failure = runCatching { router.getDownloadProgress(1) }.exceptionOrNull()

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
