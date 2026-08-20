package org.monogram.data.mtproto

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.ChatType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.DialogSnapshotModel
import org.monogram.domain.repository.ChatSearchRepository
import org.monogram.domain.repository.DialogSnapshotRepository
import org.monogram.domain.repository.SearchMessagesResult
import org.monogram.mtproto.tl.generated.cloud.layer223.Peer
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.Found_bc39b7fc74
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.Search

internal class MtProtoChatSearchRepository(
    private val dialogRepository: DialogSnapshotRepository,
    private val messageStore: MtProtoMessageProjectionStore? = null,
    private val configSource: TelegramMtProtoBootstrapConfigSource? = null,
    private val transportFactory: MtProtoSessionTransportFactory? = null,
    private val userStore: MtProtoUserProjectionStore? = null,
    private val chatStore: MtProtoChatProjectionStore? = null,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : ChatSearchRepository {
    override val searchHistory: Flow<List<ChatModel>> = emptyFlow()

    override suspend fun searchChats(query: String): List<ChatModel> {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return emptyList()
        return dialogRepository.getDialogs(accountId)
            .asSequence()
            .filter { it.matches(normalized) }
            .mapNotNull { it.toChatModel() }
            .toList()
    }

    override suspend fun searchPublicChats(query: String): List<ChatModel> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return emptyList()
        val config = configSource?.createForAccount(accountId)
            ?: unsupported("MTProto public chat search is not available")
        val factory = transportFactory
            ?: unsupported("MTProto public chat search is not available")
        val users = userStore
            ?: unsupported("MTProto public chat search is not available")
        val chats = chatStore
            ?: unsupported("MTProto public chat search is not available")
        val scope = MtProtoAuthKeyScope(accountId, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val transport = factory.open(accountId)
        val result = try {
            transport.execute(Search(normalized, MAX_PUBLIC_SEARCH_RESULTS))
        } finally {
            transport.close()
        } as? Found_bc39b7fc74 ?: error("Unsupported contacts.search result")
        users.upsert(scope, result.users)
        chats.upsert(scope, result.chats)
        return result.results.mapNotNull { it.toChatModel(scope, users, chats) }
    }

    override suspend fun searchMessages(query: String, offset: String, limit: Int): SearchMessagesResult {
        require(limit in 1..MAX_SEARCH_PAGE_SIZE) { "Search page size must be between 1 and $MAX_SEARCH_PAGE_SIZE" }
        val normalized = query.trim()
        if (normalized.isEmpty()) return SearchMessagesResult(emptyList(), "")
        val start = when {
            offset.isEmpty() -> 0
            else -> offset.toIntOrNull()?.takeIf { it >= 0 }
                ?: throw IllegalArgumentException("MTProto message search offset is invalid")
        }
        val store = messageStore ?: unsupported("MTProto message search is not available")
        val config = configSource?.createForAccount(accountId)
            ?: unsupported("MTProto message search is not available")
        val scope = MtProtoAuthKeyScope(accountId, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val rows = store.search(scope, normalized, limit + 1, start)
        val page = rows.take(limit)
        val nextOffset = if (rows.size > limit) (start + limit).toString() else ""
        return SearchMessagesResult(page.map { it.toDomain() }, nextOffset)
    }

    override fun addSearchChatId(chatId: Long) = unsupported("MTProto search history is not available")
    override fun removeSearchChatId(chatId: Long) = unsupported("MTProto search history is not available")
    override fun clearSearchHistory() = unsupported("MTProto search history is not available")

    private fun DialogSnapshotModel.matches(query: String): Boolean =
        title.orEmpty().lowercase().contains(query) || username.orEmpty().lowercase().contains(query)

    private fun DialogSnapshotModel.toChatModel() = takeIf {
        isPeerResolved && !isPeerDeleted && !isPeerForbidden && peerType != DialogPeerType.UNKNOWN
    }?.let {
        ChatModel(
            id = TelegramPeerChatId.encode(peerType, peerId),
            title = title?.takeIf(String::isNotBlank) ?: username.orEmpty().ifBlank { peerId.toString() },
            unreadCount = unreadCount,
            lastMessageText = latestMessage.text.orEmpty(),
            lastMessageDate = latestMessage.date,
            lastMessageId = latestMessage.messageId,
            isLastMessageOutgoing = latestMessage.isOutgoing,
            messageSenderId = latestMessage.senderId,
            lastMessageContentType = if (latestMessage.hasMedia) "media" else "text",
            username = username,
            type = when (peerType) {
                DialogPeerType.PRIVATE -> ChatType.PRIVATE
                DialogPeerType.BASIC_GROUP -> ChatType.BASIC_GROUP
                DialogPeerType.SUPERGROUP,
                DialogPeerType.CHANNEL -> ChatType.SUPERGROUP
                DialogPeerType.UNKNOWN -> return@let null
            },
            isGroup = peerType != DialogPeerType.PRIVATE,
            isSupergroup = peerType == DialogPeerType.SUPERGROUP || peerType == DialogPeerType.CHANNEL,
            isChannel = peerType == DialogPeerType.CHANNEL,
        )
    }

    private fun MtProtoMessageReadModel.toDomain() = MessageModel(
        id = messageId.toLong(),
        date = date,
        isOutgoing = isOutgoing,
        senderName = "",
        chatId = TelegramPeerChatId.encode(peerType.toDialogPeerType(), peerId),
        content = if (isService) MessageContent.Service(text.orEmpty()) else MessageContent.Text(text.orEmpty()),
        senderId = senderId ?: 0L,
        editDate = editDate ?: 0,
        mediaAlbumId = groupedId ?: 0L,
        isPinned = isPinned,
    )

    private suspend fun Peer.toChatModel(
        scope: MtProtoAuthKeyScope,
        users: MtProtoUserProjectionStore,
        chats: MtProtoChatProjectionStore,
    ): ChatModel? = when (this) {
        is PeerUser -> users.get(scope, userId)?.takeIf { !it.isDeleted }?.let { user ->
            ChatModel(
                id = TelegramPeerChatId.encode(DialogPeerType.PRIVATE, userId),
                title = listOfNotNull(user.firstName, user.lastName).joinToString(" ").ifBlank { user.username.orEmpty().ifBlank { userId.toString() } },
                unreadCount = 0,
                username = user.username,
                type = ChatType.PRIVATE,
            )
        }
        is PeerChat -> chats.get(scope, chatId)?.takeIf { !it.isDeleted && !it.isForbidden }?.let { chat ->
            ChatModel(
                id = TelegramPeerChatId.encode(DialogPeerType.BASIC_GROUP, chatId),
                title = chat.title.orEmpty().ifBlank { chatId.toString() },
                unreadCount = 0,
                username = chat.username,
                type = ChatType.BASIC_GROUP,
                isGroup = true,
            )
        }
        is PeerChannel -> chats.get(scope, channelId)?.takeIf { !it.isDeleted && !it.isForbidden }?.let { chat ->
            val peerType = if (chat.type == MtProtoChatType.CHANNEL) DialogPeerType.CHANNEL else DialogPeerType.SUPERGROUP
            ChatModel(
                id = TelegramPeerChatId.encode(peerType, channelId),
                title = chat.title.orEmpty().ifBlank { channelId.toString() },
                unreadCount = 0,
                username = chat.username,
                type = ChatType.SUPERGROUP,
                isGroup = true,
                isSupergroup = true,
                isChannel = peerType == DialogPeerType.CHANNEL,
            )
        }
    }

    private fun MtProtoMessagePeerType.toDialogPeerType() = when (this) {
        MtProtoMessagePeerType.USER -> DialogPeerType.PRIVATE
        MtProtoMessagePeerType.GROUP -> DialogPeerType.BASIC_GROUP
        MtProtoMessagePeerType.CHANNEL -> DialogPeerType.CHANNEL
    }

    private fun unsupported(message: String): Nothing = throw UnsupportedOperationException(message)

    private companion object {
        const val MAX_SEARCH_PAGE_SIZE = 100
        const val MAX_PUBLIC_SEARCH_RESULTS = 50

        const val DEFAULT_ACCOUNT_ID = "default"
    }
}
