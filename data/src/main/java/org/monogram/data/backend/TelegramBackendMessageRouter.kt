package org.monogram.data.backend

import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageHistoryCursorModel
import org.monogram.domain.models.MessageHistorySnapshotModel
import org.monogram.domain.models.MessageHistorySnapshotRequest
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.repository.BoundaryState
import org.monogram.domain.repository.HistoryPage
import org.monogram.domain.repository.HistorySource
import org.monogram.domain.repository.HistoryRequest
import org.monogram.domain.repository.MessageRepository
import org.monogram.domain.repository.MessageHistorySnapshotRepository
import org.monogram.domain.repository.RichTextParsingRepository
import org.monogram.data.mtproto.MtProtoDeleteMessageRepository
import org.monogram.data.mtproto.MtProtoDraftRepository
import org.monogram.data.mtproto.MtProtoPinnedMessageRepository
import org.monogram.data.mtproto.MtProtoMessageReadModel
import org.monogram.data.mtproto.MtProtoScheduledMessageOperations
import org.monogram.data.mtproto.MtProtoMessageViewerReader
import org.monogram.domain.repository.MtProtoTextMessageRepository
import org.monogram.data.mtproto.MtProtoPinnedMessageReader
import org.monogram.data.mtproto.MtProtoFileRepository

/**
 * Keeps TDLib-owned message commands unavailable when the account uses the Kotlin MTProto
 * backend. The snapshot repositories own the currently supported MTProto read path instead.
 */
internal class TelegramBackendMessageRouter(
    selectionStore: TelegramBackendSelectionStore,
    legacyFactory: () -> MessageRepository,
    private val draftFactory: () -> MtProtoDraftRepository,
    private val deleteFactory: () -> MtProtoDeleteMessageRepository = {
        MtProtoDeleteMessageRepository { _, _, _ -> }
    },
    private val pinnedFactory: () -> MtProtoPinnedMessageRepository = {
        MtProtoPinnedMessageRepository { _, _, _ -> }
    },
    private val scheduledFactory: () -> MtProtoScheduledMessageOperations = {
        error("MTProto scheduled message repository is not configured")
    },
    private val pinnedReadFactory: () -> MtProtoPinnedMessageReader = {
        error("MTProto pinned message read repository is not configured")
    },
    private val textFactory: () -> MtProtoTextMessageRepository = {
        error("MTProto text message repository is not configured")
    },
    private val viewerFactory: () -> MtProtoMessageViewerReader = {
        error("MTProto message viewer reader is not configured")
    },
    private val fileFactory: () -> MtProtoFileRepository = {
        error("MTProto file repository is not configured")
    },
    private val historyRepository: MessageHistorySnapshotRepository? = null,
    scope: CoroutineScope,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)
    private val drafts by lazy(LazyThreadSafetyMode.NONE, draftFactory)
    private val deletion by lazy(LazyThreadSafetyMode.NONE, deleteFactory)
    private val pinned by lazy(LazyThreadSafetyMode.NONE, pinnedFactory)
    private val scheduled by lazy(LazyThreadSafetyMode.NONE, scheduledFactory)
    private val pinnedRead by lazy(LazyThreadSafetyMode.NONE, pinnedReadFactory)
    private val text by lazy(LazyThreadSafetyMode.NONE, textFactory)
    private val viewers by lazy(LazyThreadSafetyMode.NONE, viewerFactory)
    private val files by lazy(LazyThreadSafetyMode.NONE, fileFactory)

    val repository: MessageRepository = Proxy.newProxyInstance(
        MessageRepository::class.java.classLoader,
        arrayOf(MessageRepository::class.java, RichTextParsingRepository::class.java),
        MessageInvocationHandler()
    ) as MessageRepository

    init {
        scope.launch {
            selectionStore.observe(accountId).collect { selectedBackend.value = it }
        }
    }

    private inner class MessageInvocationHandler : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) {
                return when (method.name) {
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "TelegramBackendMessageRouter"
                    else -> error("Unsupported Object method: ${method.name}")
                }
            }

            return when (selectedBackend.value) {
                TelegramBackendKind.LEGACY -> invokeLegacy(method, args)
                TelegramBackendKind.KOTLIN_MTPROTO -> when (method.name) {
                    "getFileDownloadFlow" -> files.fileDownloadFlow
                    "getMessageDownloadFlow" -> files.messageDownloadFlow
                    "downloadFile" -> {
                        val values = requireNotNull(args) { "Missing arguments for ${method.name}" }
                        files.download(
                            fileId = values[0] as Int,
                            offset = values[2] as Long,
                            limit = values[3] as Long,
                        )
                        Unit
                    }
                    "cancelDownloadFile" -> invokeDraft(method, args) { values -> files.cancel(values[0] as Int) }
                    "getFilePath" -> invokeDraft(method, args) { values -> files.getPath(values[0] as Int) }
                    "getFileInfo" -> invokeDraft(method, args) { values -> files.getInfo(values[0] as Int) }
                    "openChat", "closeChat" -> invokeDraft(method, args) { Unit }
                    "sendMessage" -> invokeDraft(method, args) { values ->
                        val options = values[5] as org.monogram.domain.models.MessageSendOptions
                        text.sendText(
                            chatId = values[0] as Long,
                            peerType = TelegramPeerChatId.decode(values[0] as Long).type,
                            text = values[1] as String,
                            silent = options.silent,
                            scheduleDate = options.scheduleDate,
                            disableLinkPreview = options.disableLinkPreview,
                            replyToMessageId = values[2] as Long?,
                            threadId = values[4] as Long?,
                            entities = values[3] as List<org.monogram.domain.models.MessageEntity>,
                        )
                    }
                    "editMessage" -> invokeDraft(method, args) { values ->
                        text.editText(
                            chatId = values[0] as Long,
                            peerType = TelegramPeerChatId.decode(values[0] as Long).type,
                            messageId = values[1] as Long,
                            text = values[2] as String,
                            entities = values[3] as List<org.monogram.domain.models.MessageEntity>,
                        )
                    }
                    "addMessageReaction" -> invokeDraft(method, args) { values ->
                        text.setEmojiReaction(
                            values[0] as Long,
                            TelegramPeerChatId.decode(values[0] as Long).type,
                            values[1] as Long,
                            values[2] as String,
                        )
                    }
                    "removeMessageReaction" -> invokeDraft(method, args) { values ->
                        text.setEmojiReaction(
                            values[0] as Long,
                            TelegramPeerChatId.decode(values[0] as Long).type,
                            values[1] as Long,
                            null,
                        )
                    }
                    "sendChatAction" -> invokeDraft(method, args) { values ->
                        when (values[1]) {
                            MessageRepository.ChatAction.Typing -> text.sendTyping(
                                values[0] as Long,
                                TelegramPeerChatId.decode(values[0] as Long).type,
                                values[2] as Long?,
                            )
                            else -> unsupported(method)
                        }
                    }
                    "getMessageViewers" -> invokeDraft(method, args) { values ->
                        viewers.get(values[0] as Long, values[1] as Long)
                    }
                    "markAllMentionsAsRead" -> invokeDraft(method, args) { values ->
                        text.markMentionsRead(values[0] as Long, TelegramPeerChatId.decode(values[0] as Long).type)
                    }
                    "markAllReactionsAsRead" -> invokeDraft(method, args) { values ->
                        text.markReactionsRead(values[0] as Long, TelegramPeerChatId.decode(values[0] as Long).type)
                    }
                    "getHistoryPage" -> invokeDraft(method, args) { values ->
                        getHistoryPage(values[0] as HistoryRequest)
                    }
                    "getPinnedMessage" -> invokeDraft(method, args) { values ->
                        pinnedRead.get(values[0] as Long, values[1] as Long?).firstOrNull()?.toMessageModel(values[0] as Long)
                    }
                    "getAllPinnedMessages" -> invokeDraft(method, args) { values ->
                        buildList {
                            pinnedRead.get(values[0] as Long, values[1] as Long?).forEach {
                                add(it.toMessageModel(values[0] as Long))
                            }
                        }
                    }
                    "getPinnedMessageCount" -> invokeDraft(method, args) { values ->
                        pinnedRead.get(values[0] as Long, values[1] as Long?).size
                    }
                    "sendScheduledNow" -> invokeDraft(method, args) { values ->
                        scheduled.sendNow(values[0] as Long, values[1] as Long)
                    }
                    "getScheduledMessages" -> invokeDraft(method, args) { values ->
                        buildList {
                            scheduled.get(values[0] as Long).forEach { message ->
                                add(message.toMessageModel(values[0] as Long))
                            }
                        }
                    }
                    "getChatDraft" -> invokeDraft(method, args) { values ->
                        drafts.getDraft(values[0] as Long, values[1] as Long?)
                    }
                    "saveChatDraft" -> invokeDraft(method, args) { values ->
                        drafts.saveDraft(values[0] as Long, values[1] as String, values[2] as Long?, values[3] as Long?)
                    }
                    "forwardMessage" -> invokeDraft(method, args) { values ->
                        text.forwardMessages(
                            org.monogram.domain.repository.ForwardRequest(
                                fromChatId = values[1] as Long,
                                messageIds = listOf(values[2] as Long),
                                targets = listOf(org.monogram.domain.repository.ForwardTarget(values[0] as Long)),
                                options = org.monogram.domain.repository.ForwardOptions(sendCopy = values[3] as Boolean),
                            ),
                        )
                    }
                    "forwardMessages" -> invokeDraft(method, args) { values ->
                        text.forwardMessages(values[0] as org.monogram.domain.repository.ForwardRequest)
                    }
                    "deleteMessage" -> invokeDraft(method, args) { values ->
                        deletion.delete(values[0] as Long, values[1] as List<Long>, values[2] as Boolean)
                    }
                    "pinMessage" -> invokeDraft(method, args) { values ->
                        pinned.setPinned(values[0] as Long, values[1] as Long, pinned = true)
                    }
                    "unpinMessage" -> invokeDraft(method, args) { values ->
                        pinned.setPinned(values[0] as Long, values[1] as Long, pinned = false)
                    }
                    else -> unsupported(method)
                }

                null -> error("Telegram backend selection is not loaded")
            }
        }
    }

    private fun invokeDraft(
        method: Method,
        args: Array<out Any?>?,
        operation: suspend (Array<out Any?>) -> Any?,
    ): Any? {
        val values = requireNotNull(args) { "Missing arguments for ${method.name}" }
        @Suppress("UNCHECKED_CAST")
        val continuation = values.last() as? Continuation<Any?>
            ?: error("Missing continuation for ${method.name}")
        suspend { operation(values) }.startCoroutine(continuation)
        return COROUTINE_SUSPENDED
    }

    private suspend fun getHistoryPage(request: HistoryRequest): HistoryPage {
        val repository = historyRepository ?: throw UnsupportedOperationException(
            "MTProto does not support getHistoryPage through MessageRepository"
        )
        val peer = TelegramPeerChatId.decode(request.key.chatId)
        val page = repository.getHistory(
            MessageHistorySnapshotRequest(
                accountId = accountId,
                peerType = peer.type,
                peerId = peer.id,
                before = (request.anchor as? org.monogram.domain.repository.HistoryAnchor.Message)?.let {
                    MessageHistoryCursorModel(0, it.id)
                },
                limit = request.limit,
            ),
        )
        return HistoryPage(
            messages = buildList {
                page.messages.forEach { message ->
                    add(message.toMessageModel(request.key.chatId))
                }
            },
            olderBoundary = if (page.nextCursor == null) BoundaryState.Reached else BoundaryState.Open,
            newerBoundary = BoundaryState.Reached,
            source = HistorySource.RoomSnapshot,
        )
    }

    private suspend fun MtProtoMessageReadModel.toMessageModel(chatId: Long): MessageModel = toMessageModel(
        chatId = chatId,
        messageId = messageId.toLong(),
        date = date,
        text = text,
        isService = isService,
        isOutgoing = isOutgoing,
        senderId = senderId,
        editDate = editDate,
        groupedId = groupedId,
        isPinned = isPinned,
        documentId = documentId,
    )

    private suspend fun MessageHistorySnapshotModel.toMessageModel(chatId: Long): MessageModel = toMessageModel(
        chatId = chatId,
        messageId = messageId,
        date = date,
        text = text,
        isService = isService,
        isOutgoing = isOutgoing,
        senderId = senderId,
        editDate = editDate,
        groupedId = groupedId,
        isPinned = isPinned,
        documentId = documentId,
    )

    private suspend fun toMessageModel(
        chatId: Long,
        messageId: Long,
        date: Int,
        text: String?,
        isService: Boolean,
        isOutgoing: Boolean,
        senderId: Long?,
        editDate: Int?,
        groupedId: Long?,
        isPinned: Boolean,
        documentId: Long?,
    ): MessageModel {
        val content = when {
            isService -> MessageContent.Service(text.orEmpty())
            documentId != null -> files.registerDocument(documentId, chatId, messageId)?.let { document ->
                MessageContent.Document(
                    path = files.getPath(document.fileId),
                    fileName = document.fileName,
                    mimeType = document.mimeType,
                    size = document.size,
                    caption = text.orEmpty(),
                    fileId = document.fileId,
                )
            } ?: MessageContent.Text(text.orEmpty())
            else -> MessageContent.Text(text.orEmpty())
        }
        return MessageModel(
            id = messageId,
            date = date,
            isOutgoing = isOutgoing,
            senderName = "",
            chatId = chatId,
            content = content,
            senderId = senderId ?: 0L,
            editDate = editDate ?: 0,
            mediaAlbumId = groupedId ?: 0L,
            isPinned = isPinned,
        )
    }

    private fun invokeLegacy(method: Method, args: Array<out Any?>?): Any? = try {
        method.invoke(legacy, *(args ?: emptyArray()))
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }

    private fun unsupported(method: Method): Nothing = throw UnsupportedOperationException(
        "MTProto does not support ${method.name} through MessageRepository"
    )

    private companion object {
        const val DEFAULT_ACCOUNT_ID = "default"
    }
}
