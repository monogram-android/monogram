package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.domain.repository.MessageRepository
import org.monogram.data.mtproto.MtProtoDraftRepository

class TelegramBackendMessageRouterTest {
    @Test
    fun `MTProto message commands fail closed without creating legacy repository`() = runBlocking {
        val router = TelegramBackendMessageRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy message repository must not be created") },
            draftFactory = { error("draft repository failure") },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { router.repository.getChatDraft(1L) }
        }
        Unit
    }

    @Test
    fun `legacy message commands delegate to the legacy repository`() = runBlocking {
        val legacy = Proxy.newProxyInstance(
            MessageRepository::class.java.classLoader,
            arrayOf(MessageRepository::class.java),
        ) { _, method, _ ->
            if (method.name == "getChatDraft") "legacy draft" else error("Unexpected ${method.name}")
        } as MessageRepository
        val router = TelegramBackendMessageRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.LEGACY),
            legacyFactory = { legacy },
            draftFactory = { error("draft repository must not be created") },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals("legacy draft", router.repository.getChatDraft(1L))
    }

    @Test
    fun `MTProto draft commands use the selected repository`() = runBlocking {
        val drafts = FakeMtProtoDraftRepository()
        val router = TelegramBackendMessageRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy message repository must not be created") },
            draftFactory = { drafts },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        router.repository.saveChatDraft(1L, "draft", null)

        assertEquals("draft", router.repository.getChatDraft(1L))
        assertEquals("draft", drafts.text)
    }

    @Test
    fun `MTProto message update flows are inert`() = runBlocking {
        val router = TelegramBackendMessageRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy message repository must not be created") },
            draftFactory = { error("draft repository must not be created") },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertNull(router.repository.newMessageFlow.firstOrNull())
    }

    private class FakeMtProtoDraftRepository : MtProtoDraftRepository {
        var text: String? = null

        override suspend fun getDraft(chatId: Long, threadId: Long?) = text

        override suspend fun saveDraft(chatId: Long, text: String, replyToMsgId: Long?, threadId: Long?) {
            this.text = text
        }
    }

    private class FakeSelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        private val value = MutableStateFlow(initial)

        override suspend fun get(accountId: String) = value.value
        override fun observe(accountId: String): Flow<TelegramBackendKind> = value
        override suspend fun select(accountId: String, backend: TelegramBackendKind) { value.value = backend }
        override suspend fun reset(accountId: String) { value.value = TelegramBackendKind.LEGACY }
    }
}
