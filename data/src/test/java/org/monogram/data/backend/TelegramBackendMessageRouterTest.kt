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
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.MessageHistorySnapshotModel
import org.monogram.domain.models.MessageHistorySnapshotPage
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.repository.HistoryAnchor
import org.monogram.domain.repository.HistoryDirection
import org.monogram.domain.repository.HistoryRequest
import org.monogram.domain.repository.ConversationKey
import org.monogram.domain.repository.MessageHistorySnapshotRepository
import org.monogram.domain.repository.MessageRepository
import org.monogram.data.mtproto.MtProtoDeleteMessageRepository
import org.monogram.data.mtproto.MtProtoDraftRepository
import org.monogram.data.mtproto.MtProtoPinnedMessageRepository

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
    fun `MTProto history uses the selected snapshot repository`() = runBlocking {
        var accountId: String? = null
        val router = TelegramBackendMessageRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy message repository must not be created") },
            draftFactory = { error("draft repository must not be created") },
            historyRepository = object : MessageHistorySnapshotRepository {
                override suspend fun getHistory(request: org.monogram.domain.models.MessageHistorySnapshotRequest): MessageHistorySnapshotPage {
                    accountId = request.accountId
                    return MessageHistorySnapshotPage(
                        messages = listOf(MessageHistorySnapshotModel(7, 9, 100, "hello", false, false, false, false, false, false, false, null, null, false)),
                        nextCursor = null,
                    )
                }
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        val page = router.repository.getHistoryPage(
            HistoryRequest(
                key = ConversationKey(TelegramPeerChatId.encode(DialogPeerType.PRIVATE, 42)),
                anchor = HistoryAnchor.Latest,
                direction = HistoryDirection.Initial,
                limit = 20,
            ),
        )

        assertEquals("default", accountId)
        assertEquals("hello", (page.messages.single().content as org.monogram.domain.models.MessageContent.Text).text)
        assertEquals(org.monogram.domain.repository.BoundaryState.Reached, page.olderBoundary)
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
    fun `MTProto delete mutations use the selected repository without creating legacy`() = runBlocking {
        val deletion = RecordingDeleteMessageRepository()
        val router = TelegramBackendMessageRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy message repository must not be created") },
            draftFactory = { error("draft repository must not be created") },
            deleteFactory = { deletion },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        router.repository.deleteMessage(1L, listOf(2L, 3L), revoke = true)

        assertEquals(listOf(Triple(1L, listOf(2L, 3L), true)), deletion.requests)
    }

    @Test
    fun `MTProto pin mutations use the selected repository without creating legacy`() = runBlocking {
        val pinned = RecordingPinnedMessageRepository()
        val router = TelegramBackendMessageRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy message repository must not be created") },
            draftFactory = { error("draft repository must not be created") },
            pinnedFactory = { pinned },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        router.repository.pinMessage(1L, 2L)
        router.repository.unpinMessage(1L, 2L)

        assertEquals(listOf(Triple(1L, 2L, true), Triple(1L, 2L, false)), pinned.requests)
    }

    @Test
    fun `MTProto chat lifecycle does not initialize TDLib repository`() = runBlocking {
        val router = TelegramBackendMessageRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy message repository must not be created") },
            draftFactory = { error("draft repository must not be created") },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        router.repository.openChat(1L, "conversation")
        router.repository.closeChat(1L, "conversation")
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

    private class RecordingDeleteMessageRepository : MtProtoDeleteMessageRepository {
        val requests = mutableListOf<Triple<Long, List<Long>, Boolean>>()

        override suspend fun delete(chatId: Long, messageIds: List<Long>, revoke: Boolean) {
            requests += Triple(chatId, messageIds, revoke)
        }
    }

    private class RecordingPinnedMessageRepository : MtProtoPinnedMessageRepository {
        val requests = mutableListOf<Triple<Long, Long, Boolean>>()

        override suspend fun setPinned(chatId: Long, messageId: Long, pinned: Boolean) {
            requests += Triple(chatId, messageId, pinned)
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
