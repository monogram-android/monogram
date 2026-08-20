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
import org.monogram.data.mtproto.MtProtoScheduledMessageRepository
import org.monogram.domain.repository.MtProtoTextMessageRepository
import org.monogram.data.mtproto.MtProtoPinnedMessageReader

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
    private val scheduledFactory: () -> MtProtoScheduledMessageRepository = {
        error("MTProto scheduled message repository is not configured")
    },
    private val pinnedReadFactory: () -> MtProtoPinnedMessageReader = {
        error("MTProto pinned message read repository is not configured")
    },
    private val textFactory: () -> MtProtoTextMessageRepository = {
        error("MTProto text message repository is not configured")
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
                    "openChat", "closeChat" -> invokeDraft(method, args) { Unit }
                    "getHistoryPage" -> invokeDraft(method, args) { values ->
                        getHistoryPage(values[0] as HistoryRequest)
                    }
                    "getPinnedMessage" -> invokeDraft(method, args) { values ->
                        pinnedRead.get(values[0] as Long, values[1] as Long?).firstOrNull()?.toMessageModel(values[0] as Long)
                    }
                    "getAllPinnedMessages" -> invokeDraft(method, args) { values ->
                        pinnedRead.get(values[0] as Long, values[1] as Long?).map { it.toMessageModel(values[0] as Long) }
                    }
                    "getPinnedMessageCount" -> invokeDraft(method, args) { values ->
                        pinnedRead.get(values[0] as Long, values[1] as Long?).size
                    }
                    "getScheduledMessages" -> invokeDraft(method, args) { values ->
                        scheduled.get(values[0] as Long).map { message ->
                            MessageModel(
                                id = message.messageId.toLong(),
                                date = message.date,
                                isOutgoing = message.isOutgoing,
                                senderName = "",
                                chatId = values[0] as Long,
                                content = if (message.isService) MessageContent.Service(message.text.orEmpty()) else MessageContent.Text(message.text.orEmpty()),
                                senderId = message.senderId ?: 0L,
                                editDate = message.editDate ?: 0,
                                mediaAlbumId = message.groupedId ?: 0L,
                                isPinned = message.isPinned,
                            )
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
            messages = page.messages.map { message ->
                MessageModel(
                    id = message.messageId,
                    date = message.date,
                    isOutgoing = message.isOutgoing,
                    senderName = "",
                    chatId = request.key.chatId,
                    content = if (message.isService) MessageContent.Service(message.text.orEmpty()) else MessageContent.Text(message.text.orEmpty()),
                    senderId = message.senderId ?: 0L,
                    editDate = message.editDate ?: 0,
                    mediaAlbumId = message.groupedId ?: 0L,
                    isPinned = message.isPinned,
                )
            },
            olderBoundary = if (page.nextCursor == null) BoundaryState.Reached else BoundaryState.Open,
            newerBoundary = BoundaryState.Reached,
            source = HistorySource.RoomSnapshot,
        )
    }

    private fun MtProtoMessageReadModel.toMessageModel(chatId: Long) = MessageModel(
        id = messageId.toLong(),
        date = date,
        isOutgoing = isOutgoing,
        senderName = "",
        chatId = chatId,
        content = if (isService) MessageContent.Service(text.orEmpty()) else MessageContent.Text(text.orEmpty()),
        senderId = senderId ?: 0L,
        editDate = editDate ?: 0,
        mediaAlbumId = groupedId ?: 0L,
        isPinned = isPinned,
    )

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
