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
import org.monogram.domain.repository.LinkHandlerRepository

class TelegramBackendLinkHandlerRouterTest {
    @Test
    fun `selected MTProto link operations fail closed without creating legacy handler`() = runBlocking {
        val router = TelegramBackendLinkHandlerRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy link handler must not be created") },
            mtProtoFactory = {
                object : MtProtoLinkHandler {
                    override suspend fun handle(link: String) = LinkAction.OpenUser(7)
                    override suspend fun joinChat(inviteLink: String) = 9L
                    override suspend fun joinChatAction(inviteLink: String) = LinkAction.OpenChat(9L)
                }
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(LinkAction.OpenUser(7), router.handleLink("https://t.me/example"))
        assertEquals(9L, router.joinChat("https://t.me/+invite"))
        assertEquals(LinkAction.OpenChat(9L), router.joinChatAction("https://t.me/+invite"))
    }

    @Test
    fun `link handling follows rollback backend selection`() = runBlocking {
        val selection = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO)
        var legacyCreated = 0
        val router = TelegramBackendLinkHandlerRouter(
            selectionStore = selection,
            legacyFactory = {
                legacyCreated++
                object : LinkHandlerRepository {
                    override suspend fun handleLink(link: String) = LinkAction.OpenUser(2)
                    override suspend fun joinChat(inviteLink: String) = 22L
                    override suspend fun joinChatAction(inviteLink: String) = LinkAction.OpenChat(22L)
                }
            },
            mtProtoFactory = {
                object : MtProtoLinkHandler {
                    override suspend fun handle(link: String) = LinkAction.OpenUser(1)
                    override suspend fun joinChat(inviteLink: String) = 11L
                    override suspend fun joinChatAction(inviteLink: String) = LinkAction.OpenChat(11L)
                }
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(LinkAction.OpenUser(1), router.handleLink("https://t.me/example"))
        assertEquals(11L, router.joinChat("https://t.me/+invite"))
        assertEquals(LinkAction.OpenChat(11L), router.joinChatAction("https://t.me/+invite"))
        assertEquals(0, legacyCreated)

        selection.select("default", TelegramBackendKind.LEGACY)
        assertEquals(LinkAction.OpenUser(2), router.handleLink("https://t.me/example"))
        assertEquals(22L, router.joinChat("https://t.me/+invite"))
        assertEquals(LinkAction.OpenChat(22L), router.joinChatAction("https://t.me/+invite"))
        assertEquals(1, legacyCreated)

        selection.select("default", TelegramBackendKind.KOTLIN_MTPROTO)
        assertEquals(LinkAction.OpenUser(1), router.handleLink("https://t.me/example"))
        assertEquals(11L, router.joinChat("https://t.me/+invite"))
    }

    private class FakeSelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        private val state = MutableStateFlow(initial)
        override suspend fun get(accountId: String) = state.value
        override fun observe(accountId: String): Flow<TelegramBackendKind> = state
        override suspend fun select(accountId: String, backend: TelegramBackendKind) { state.value = backend }
        override suspend fun reset(accountId: String) { state.value = TelegramBackendKind.LEGACY }
    }
}
