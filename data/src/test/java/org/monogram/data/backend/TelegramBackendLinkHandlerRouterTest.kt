package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.data.mtproto.MtProtoLinkHandler
import org.monogram.domain.repository.LinkAction

class TelegramBackendLinkHandlerRouterTest {
    @Test
    fun `selected MTProto link operations fail closed without creating legacy handler`() = runBlocking {
        val router = TelegramBackendLinkHandlerRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy link handler must not be created") },
            mtProtoFactory = {
                object : MtProtoLinkHandler {
                    override suspend fun handle(link: String) = LinkAction.OpenUser(7)
                }
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(LinkAction.OpenUser(7), router.handleLink("https://t.me/example"))
    }

    private class FakeSelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        private val state = MutableStateFlow(initial)
        override suspend fun get(accountId: String) = state.value
        override fun observe(accountId: String): Flow<TelegramBackendKind> = state
        override suspend fun select(accountId: String, backend: TelegramBackendKind) { state.value = backend }
        override suspend fun reset(accountId: String) { state.value = TelegramBackendKind.LEGACY }
    }
}
