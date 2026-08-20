package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
import org.monogram.data.mtproto.MtProtoPinnedMessageReader
import org.monogram.data.mtproto.MtProtoMessagePeerType
import org.monogram.data.mtproto.MtProtoMessageReadModel
import org.monogram.data.mtproto.MtProtoScheduledMessageOperations
import org.monogram.domain.repository.MtProtoTextMessageRepository

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
    fun `MTProto maps all pinned messages from the selected reader`() = runBlocking {
        val router = TelegramBackendMessageRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy message repository must not be created") },
            draftFactory = { error("draft repository must not be created") },
            pinnedReadFactory = {
                MtProtoPinnedMessageReader { _, _ ->
                    listOf(
                    MtProtoMessageReadModel(
                        peerType = MtProtoMessagePeerType.USER,
                        peerId = 1L,
                        messageId = 7,
                        senderType = MtProtoMessagePeerType.USER,
                        senderId = 2L,
                        date = 100,
                        text = "pinned",
                        isService = false,
                        isDeleted = false,
                        isOutgoing = false,
                        isMentioned = false,
                        isMediaUnread = false,
                        isSilent = false,
                        isPinned = true,
                        editDate = null,
                        groupedId = null,
                        hasMedia = false,
                    ),
                    )
                }
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        val messages = router.repository.getAllPinnedMessages(1L)

        assertEquals(listOf(7L), messages.map { it.id })
        assertEquals("pinned", (messages.single().content as org.monogram.domain.models.MessageContent.Text).text)
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
    fun `MTProto sends scheduled messages through selected repository`() = runBlocking {
        val scheduled = RecordingScheduledMessages()
        val router = TelegramBackendMessageRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy message repository must not be created") },
            draftFactory = { error("draft repository must not be created") },
            scheduledFactory = { scheduled },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        router.repository.sendScheduledNow(3L, 7L)

        assertEquals(3L to 7L, scheduled.sent)
    }

    @Test
    fun `MTProto routes plain text mutations and receipts through selected text repository`() = runBlocking {
        val text = RecordingTextMessageRepository()
        val router = TelegramBackendMessageRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy message repository must not be created") },
            draftFactory = { error("draft repository must not be created") },
            textFactory = { text },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        router.repository.sendMessage(
            chatId = 42L,
            text = "hello",
            sendOptions = org.monogram.domain.models.MessageSendOptions(
                silent = true,
                scheduleDate = 123,
                disableLinkPreview = true,
            ),
        )
        router.repository.editMessage(42L, 7L, "edited")
        router.repository.addMessageReaction(42L, 7L, "👍")
        router.repository.removeMessageReaction(42L, 7L, "👍")
        router.repository.markAllMentionsAsRead(42L)
        router.repository.markAllReactionsAsRead(42L)
        assertEquals(listOf("hello" to true), text.sent)
        assertEquals(listOf(7L to "edited"), text.edited)
        assertEquals(listOf("👍", null), text.reactions)
        assertEquals(1, text.mentionsRead)
        assertEquals(1, text.reactionsRead)
    }

    @Test
    fun `MTProto forwards messages through selected text repository`() = runBlocking {
        val text = RecordingTextMessageRepository()
        val router = TelegramBackendMessageRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy message repository must not be created") },
            draftFactory = { error("draft repository must not be created") },
            textFactory = { text },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        router.repository.forwardMessage(toChatId = 9L, fromChatId = 3L, messageId = 7L, sendCopy = true)

        assertEquals(3L, text.request?.fromChatId)
        assertEquals(listOf(7L), text.request?.messageIds)
        assertEquals(listOf(9L), text.request?.targets?.map { it.chatId })
        assertEquals(true, text.request?.options?.sendCopy)
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
    fun `MTProto message update flows fail closed`() = runBlocking {
        val router = TelegramBackendMessageRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy message repository must not be created") },
            draftFactory = { error("draft repository must not be created") },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertThrows(UnsupportedOperationException::class.java) {
            router.repository.newMessageFlow
        }
        Unit
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

    private class RecordingScheduledMessages : MtProtoScheduledMessageOperations {
        var sent: Pair<Long, Long>? = null
        override suspend fun get(chatId: Long) = emptyList<MtProtoMessageReadModel>()
        override suspend fun sendNow(chatId: Long, messageId: Long) { sent = chatId to messageId }
    }

    private class RecordingTextMessageRepository : MtProtoTextMessageRepository {
        var request: org.monogram.domain.repository.ForwardRequest? = null
        val sent = mutableListOf<Pair<String, Boolean>>()
        val edited = mutableListOf<Pair<Long, String>>()
        val reactions = mutableListOf<String?>()
        var mentionsRead = 0
        var reactionsRead = 0
        override suspend fun sendText(chatId: Long, peerType: DialogPeerType, text: String, silent: Boolean, scheduleDate: Int?, disableLinkPreview: Boolean) {
            sent += text to silent
        }
        override suspend fun sendTyping(chatId: Long, peerType: DialogPeerType, threadId: Long?) = Unit
        override suspend fun editText(chatId: Long, peerType: DialogPeerType, messageId: Long, text: String) {
            edited += messageId to text
        }
        override suspend fun setEmojiReaction(chatId: Long, peerType: DialogPeerType, messageId: Long, emoji: String?) {
            reactions += emoji
        }
        override suspend fun setPinned(chatId: Long, peerType: DialogPeerType, messageId: Long, pinned: Boolean) = Unit
        override suspend fun forwardToSelf(chatId: Long, peerType: DialogPeerType, messageId: Long) = Unit
        override suspend fun forwardMessages(request: org.monogram.domain.repository.ForwardRequest) { this.request = request }
        override suspend fun sendScheduledNow(chatId: Long, peerType: DialogPeerType, messageId: Long) = Unit
        override suspend fun clearHistory(chatId: Long, peerType: DialogPeerType, revoke: Boolean) = Unit
        override suspend fun markMentionsRead(chatId: Long, peerType: DialogPeerType) { mentionsRead++ }
        override suspend fun markReactionsRead(chatId: Long, peerType: DialogPeerType) { reactionsRead++ }
    }

    private class FakeSelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        private val value = MutableStateFlow(initial)

        override suspend fun get(accountId: String) = value.value
        override fun observe(accountId: String): Flow<TelegramBackendKind> = value
        override suspend fun select(accountId: String, backend: TelegramBackendKind) { value.value = backend }
        override suspend fun reset(accountId: String) { value.value = TelegramBackendKind.LEGACY }
    }
}
