package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.domain.repository.TelegramLinkRepository

class TelegramBackendLinkRouterTest {
    @Test
    fun `selected MTProto links use default base URL without creating legacy repository`() = runBlocking {
        val router = TelegramBackendLinkRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy link repository must not be created") },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals("https://t.me", router.baseUrl.value)
        assertEquals("https://t.me/monogram", router.buildUrl("/monogram"))
    }

    private class FakeSelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        private val state = MutableStateFlow(initial)
        override suspend fun get(accountId: String) = state.value
        override fun observe(accountId: String): Flow<TelegramBackendKind> = state
        override suspend fun select(accountId: String, backend: TelegramBackendKind) { state.value = backend }
        override suspend fun reset(accountId: String) { state.value = TelegramBackendKind.LEGACY }
    }
}
