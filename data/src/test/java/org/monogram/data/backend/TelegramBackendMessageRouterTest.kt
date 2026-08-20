package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
import org.monogram.data.mtproto.MtProtoMessageViewerReader
import org.monogram.data.mtproto.MtProtoFileRepository
import org.monogram.data.mtproto.MtProtoMediaMessageRepository
import org.monogram.domain.models.MessageSendOptions
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
    fun `MTProto file operations use the selected adapter without creating legacy`() = runBlocking {
        val files = RecordingMtProtoFiles()
        val router = TelegramBackendMessageRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy message repository must not be created") },
            draftFactory = { error("draft repository must not be created") },
            fileFactory = { files },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        router.repository.downloadFile(fileId = 7, offset = 0, limit = 0)
        router.repository.cancelDownloadFile(7)

        assertEquals(listOf(7 to (0L to 0L)), files.downloads)
        assertEquals(listOf(7), files.cancelled)
        assertEquals(files.fileDownloadFlow, router.repository.fileDownloadFlow)
        assertEquals(files.messageDownloadFlow, router.repository.messageDownloadFlow)
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
    fun `MTProto gets message viewers from the selected reader`() = runBlocking {
        val viewer = MtProtoMessageViewerReader { chatId, messageId ->
            assertEquals(42L, chatId)
            assertEquals(7L, messageId)
            listOf(
                org.monogram.domain.models.MessageViewerModel(
                    user = org.monogram.domain.models.UserModel(id = 9L, firstName = "Viewer"),
                    viewedDate = 100,
                ),
            )
        }
        val router = TelegramBackendMessageRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy message repository must not be created") },
            draftFactory = { error("draft repository must not be created") },
            viewerFactory = { viewer },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(listOf(9L to 100), router.repository.getMessageViewers(42L, 7L).map { it.user.id to it.viewedDate })
    }

    @Test
    fun `MTProto maps projected photos to opaque download handles`() = runBlocking {
        val files = RecordingMtProtoFiles().apply {
            photo = org.monogram.data.mtproto.MtProtoPhotoFile(fileId = 13, width = 640, height = 480, size = 80L)
        }
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
                            messageId = 8,
                            senderType = null,
                            senderId = null,
                            date = 100,
                            text = "caption",
                            isService = false,
                            isDeleted = false,
                            isOutgoing = false,
                            isMentioned = false,
                            isMediaUnread = false,
                            isSilent = false,
                            isPinned = false,
                            editDate = null,
                            groupedId = null,
                            hasMedia = true,
                            photoId = 19L,
                        )
                    )
                }
            },
            fileFactory = { files },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        val content = router.repository.getPinnedMessage(1L)?.content as org.monogram.domain.models.MessageContent.Photo

        assertEquals(13, content.fileId)
        assertEquals(640, content.width)
        assertEquals(480, content.height)
        assertEquals(listOf(Triple(19L, 1L, 8L)), files.registeredPhotos)
    }

    @Test
    fun `MTProto maps projected video documents to video content`() = runBlocking {
        val files = RecordingMtProtoFiles().apply {
            document = org.monogram.data.mtproto.MtProtoDocumentFile(
                fileId = 14,
                fileName = "clip.mp4",
                mimeType = "video/mp4",
                size = 42L,
                mediaKind = org.monogram.data.mtproto.MtProtoDocumentMediaKind.VIDEO,
                width = 640,
                height = 480,
                duration = 12,
                supportsStreaming = true,
            )
        }
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
                            messageId = 9,
                            senderType = null,
                            senderId = null,
                            date = 100,
                            text = "caption",
                            isService = false,
                            isDeleted = false,
                            isOutgoing = false,
                            isMentioned = false,
                            isMediaUnread = false,
                            isSilent = false,
                            isPinned = false,
                            editDate = null,
                            groupedId = null,
                            hasMedia = true,
                            documentId = 100L,
                        )
                    )
                }
            },
            fileFactory = { files },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        val content = router.repository.getPinnedMessage(1L)?.content as org.monogram.domain.models.MessageContent.Video

        assertEquals(14, content.fileId)
        assertEquals(640, content.width)
        assertEquals(12, content.duration)
        assertEquals(true, content.supportsStreaming)
    }

    @Test
    fun `MTProto maps projected round videos to video notes`() = runBlocking {
        val files = RecordingMtProtoFiles().apply {
            document = org.monogram.data.mtproto.MtProtoDocumentFile(
                fileId = 15,
                fileName = "note.mp4",
                mimeType = "video/mp4",
                size = 42L,
                mediaKind = org.monogram.data.mtproto.MtProtoDocumentMediaKind.VIDEO_NOTE,
                width = 384,
                height = 384,
                duration = 8,
            )
        }
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
                            messageId = 10,
                            senderType = null,
                            senderId = null,
                            date = 100,
                            text = null,
                            isService = false,
                            isDeleted = false,
                            isOutgoing = false,
                            isMentioned = false,
                            isMediaUnread = false,
                            isSilent = false,
                            isPinned = false,
                            editDate = null,
                            groupedId = null,
                            hasMedia = true,
                            documentId = 101L,
                        )
                    )
                }
            },
            fileFactory = { files },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        val content = router.repository.getPinnedMessage(1L)?.content as org.monogram.domain.models.MessageContent.VideoNote

        assertEquals(15, content.fileId)
        assertEquals(384, content.length)
        assertEquals(8, content.duration)
    }

    @Test
    fun `MTProto maps projected stickers with concrete identities`() = runBlocking {
        val files = RecordingMtProtoFiles().apply {
            document = org.monogram.data.mtproto.MtProtoDocumentFile(
                fileId = 16,
                documentId = 102L,
                fileName = "sticker.webp",
                mimeType = "image/webp",
                size = 42L,
                mediaKind = org.monogram.data.mtproto.MtProtoDocumentMediaKind.STICKER,
                width = 512,
                height = 512,
                stickerSetId = 103L,
                stickerEmoji = "ok",
                stickerFormat = "STATIC",
            )
        }
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
                            messageId = 11,
                            senderType = null,
                            senderId = null,
                            date = 100,
                            text = null,
                            isService = false,
                            isDeleted = false,
                            isOutgoing = false,
                            isMentioned = false,
                            isMediaUnread = false,
                            isSilent = false,
                            isPinned = false,
                            editDate = null,
                            groupedId = null,
                            hasMedia = true,
                            documentId = 102L,
                        )
                    )
                }
            },
            fileFactory = { files },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        val content = router.repository.getPinnedMessage(1L)?.content as org.monogram.domain.models.MessageContent.Sticker

        assertEquals(102L, content.id)
        assertEquals(103L, content.setId)
        assertEquals("ok", content.emoji)
        assertEquals(org.monogram.domain.models.StickerFormat.STATIC, content.format)
        assertEquals(16, content.fileId)
    }

    @Test
    fun `MTProto maps projected documents to opaque download handles`() = runBlocking {
        val files = RecordingMtProtoFiles().apply {
            document = org.monogram.data.mtproto.MtProtoDocumentFile(
                fileId = 12,
                fileName = "report.pdf",
                mimeType = "application/pdf",
                size = 42L,
            )
        }
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
                            senderType = null,
                            senderId = null,
                            date = 100,
                            text = "caption",
                            isService = false,
                            isDeleted = false,
                            isOutgoing = false,
                            isMentioned = false,
                            isMediaUnread = false,
                            isSilent = false,
                            isPinned = false,
                            editDate = null,
                            groupedId = null,
                            hasMedia = true,
                            documentId = 99L,
                        )
                    )
                }
            },
            fileFactory = { files },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        val content = router.repository.getPinnedMessage(1L)?.content as org.monogram.domain.models.MessageContent.Document

        assertEquals(12, content.fileId)
        assertEquals("report.pdf", content.fileName)
        assertEquals(listOf(Triple(99L, 1L, 7L)), files.registered)
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
            replyToMsgId = 2L,
            threadId = 4L,
            entities = listOf(
                org.monogram.domain.models.MessageEntity(
                    offset = 0,
                    length = 5,
                    type = org.monogram.domain.models.MessageEntityType.Bold,
                ),
            ),
            sendOptions = org.monogram.domain.models.MessageSendOptions(
                silent = true,
                scheduleDate = 123,
                disableLinkPreview = true,
            ),
        )
        router.repository.editMessage(
            42L,
            7L,
            "edited",
            listOf(
                org.monogram.domain.models.MessageEntity(
                    offset = 0,
                    length = 6,
                    type = org.monogram.domain.models.MessageEntityType.Italic,
                ),
            ),
        )
        router.repository.addMessageReaction(42L, 7L, "👍")
        router.repository.removeMessageReaction(42L, 7L, "👍")
        router.repository.markAllMentionsAsRead(42L)
        router.repository.markAllReactionsAsRead(42L)
        assertEquals(listOf("hello" to true), text.sent)
        assertEquals(2L to 4L, text.replyContext)
        assertEquals(listOf(org.monogram.domain.models.MessageEntityType.Bold), text.entityTypes)
        assertEquals(listOf(7L to "edited"), text.edited)
        assertEquals(listOf(org.monogram.domain.models.MessageEntityType.Italic), text.editedEntityTypes)
        assertEquals(listOf("👍", null), text.reactions)
        assertEquals(1, text.mentionsRead)
        assertEquals(1, text.reactionsRead)
    }

    @Test
    fun `MTProto forwards unsupported rich text to its selected repository`() = runBlocking {
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
            text = "timestamp",
            entities = listOf(
                org.monogram.domain.models.MessageEntity(
                    offset = 0,
                    length = 9,
                    type = org.monogram.domain.models.MessageEntityType.MediaTimestamp(1),
                ),
            ),
        )

        assertEquals(listOf(org.monogram.domain.models.MessageEntityType.MediaTimestamp(1)), text.entityTypes)
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

    @Test
    fun `MTProto routes uploaded photo and document without legacy`() = runBlocking {
        val media = RecordingMedia()
        val router = TelegramBackendMessageRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy must not be created") },
            draftFactory = { error("draft must not be created") },
            mediaFactory = { media },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        router.repository.sendPhoto(7, "photo.jpg", "caption", showCaptionAboveMedia = true, replyToMsgId = 9, threadId = 10, sendOptions = MessageSendOptions(silent = true, scheduleDate = 11))
        router.repository.sendDocument(7, "file.pdf", "doc", replyToMsgId = 12, threadId = 13)

        assertEquals(listOf("photo:7:photo.jpg:caption:0:true:9:10:true:11", "document:7:file.pdf:doc:0:12:13:false:null"), media.calls)
    }

    @Test
    fun `MTProto routes entity-bearing media captions without legacy`() = runBlocking {
        val media = RecordingMedia()
        val router = TelegramBackendMessageRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy must not be created") },
            draftFactory = { error("draft must not be created") },
            mediaFactory = { media },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        router.repository.sendPhoto(
            chatId = 7,
            photoPath = "photo.jpg",
            caption = "caption",
            captionEntities = listOf(org.monogram.domain.models.MessageEntity(0, 7, org.monogram.domain.models.MessageEntityType.Bold)),
        )
        assertEquals(listOf("photo:7:photo.jpg:caption:1:false:null:null:false:null"), media.calls)
    }

    private class RecordingMedia : MtProtoMediaMessageRepository {
        val calls = mutableListOf<String>()
        override suspend fun sendPhoto(chatId: Long, path: String, caption: String, entities: List<org.monogram.domain.models.MessageEntity>, showCaptionAboveMedia: Boolean, replyTo: Long?, threadId: Long?, options: MessageSendOptions) {
            calls += "photo:$chatId:$path:$caption:${entities.size}:$showCaptionAboveMedia:$replyTo:$threadId:${options.silent}:${options.scheduleDate}"
        }
        override suspend fun sendDocument(chatId: Long, path: String, caption: String, entities: List<org.monogram.domain.models.MessageEntity>, replyTo: Long?, threadId: Long?, options: MessageSendOptions) {
            calls += "document:$chatId:$path:$caption:${entities.size}:$replyTo:$threadId:${options.silent}:${options.scheduleDate}"
        }
    }

    private class RecordingMtProtoFiles : MtProtoFileRepository {
        override val fileDownloadFlow = MutableSharedFlow<org.monogram.domain.models.FileDownloadEvent>()
        override val messageDownloadFlow = MutableSharedFlow<org.monogram.domain.models.MessageDownloadEvent>()
        val downloads = mutableListOf<Pair<Int, Pair<Long, Long>>>()
        val cancelled = mutableListOf<Int>()
        val registered = mutableListOf<Triple<Long, Long, Long>>()
        val registeredPhotos = mutableListOf<Triple<Long, Long, Long>>()
        var document: org.monogram.data.mtproto.MtProtoDocumentFile? = null
        var photo: org.monogram.data.mtproto.MtProtoPhotoFile? = null

        override suspend fun registerDocument(documentId: Long, chatId: Long, messageId: Long): org.monogram.data.mtproto.MtProtoDocumentFile? {
            registered += Triple(documentId, chatId, messageId)
            return document
        }

        override suspend fun registerDocument(documentId: Long): org.monogram.data.mtproto.MtProtoDocumentFile? = document

        override suspend fun registerPhoto(photoId: Long, chatId: Long, messageId: Long): org.monogram.data.mtproto.MtProtoPhotoFile? {
            registeredPhotos += Triple(photoId, chatId, messageId)
            return photo
        }

        override fun download(fileId: Int, offset: Long, limit: Long) {
            downloads += fileId to (offset to limit)
        }

        override suspend fun cancel(fileId: Int) {
            cancelled += fileId
        }

        override suspend fun getPath(fileId: Int): String? = null

        override suspend fun getInfo(fileId: Int): org.monogram.domain.models.FileModel? = null
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
        var replyContext: Pair<Long?, Long?>? = null
        val entityTypes = mutableListOf<org.monogram.domain.models.MessageEntityType>()
        val editedEntityTypes = mutableListOf<org.monogram.domain.models.MessageEntityType>()
        override suspend fun sendText(chatId: Long, peerType: DialogPeerType, text: String, silent: Boolean, scheduleDate: Int?, disableLinkPreview: Boolean) {
            sent += text to silent
        }
        override suspend fun sendText(
            chatId: Long,
            peerType: DialogPeerType,
            text: String,
            silent: Boolean,
            scheduleDate: Int?,
            disableLinkPreview: Boolean,
            replyToMessageId: Long?,
            threadId: Long?,
        ) {
            sent += text to silent
            replyContext = replyToMessageId to threadId
        }
        override suspend fun sendText(
            chatId: Long,
            peerType: DialogPeerType,
            text: String,
            silent: Boolean,
            scheduleDate: Int?,
            disableLinkPreview: Boolean,
            replyToMessageId: Long?,
            threadId: Long?,
            entities: List<org.monogram.domain.models.MessageEntity>,
        ) {
            sent += text to silent
            replyContext = replyToMessageId to threadId
            entityTypes += entities.map { it.type }
        }
        override suspend fun sendTyping(chatId: Long, peerType: DialogPeerType, threadId: Long?) = Unit
        override suspend fun editText(chatId: Long, peerType: DialogPeerType, messageId: Long, text: String) {
            edited += messageId to text
        }
        override suspend fun editText(
            chatId: Long,
            peerType: DialogPeerType,
            messageId: Long,
            text: String,
            entities: List<org.monogram.domain.models.MessageEntity>,
        ) {
            edited += messageId to text
            editedEntityTypes += entities.map { it.type }
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
