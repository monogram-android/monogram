package org.monogram.data.mtproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import org.monogram.data.db.dao.SearchHistoryDao
import org.monogram.data.db.model.SearchHistoryEntity
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
import org.monogram.mtproto.tl.generated.cloud.layer223.InputMessagesFilterEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeer
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageService
import org.monogram.mtproto.tl.generated.cloud.layer223.Message_7b7ecf54a3
import org.monogram.mtproto.tl.generated.cloud.layer223.Message_73e57f95e4
import org.monogram.mtproto.tl.generated.cloud.layer223.Peer
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.Found_bc39b7fc74
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.Search
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.MessagesSlice
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.SearchGlobal

@OptIn(ExperimentalCoroutinesApi::class)
internal class MtProtoChatSearchRepository(
    private val dialogRepository: DialogSnapshotRepository,
    private val messageStore: MtProtoMessageProjectionStore? = null,
    private val configSource: TelegramMtProtoBootstrapConfigSource? = null,
    private val transportFactory: MtProtoSessionTransportFactory? = null,
    private val userStore: MtProtoUserProjectionStore? = null,
    private val chatStore: MtProtoChatProjectionStore? = null,
    private val resultStager: MtProtoHistoryResultStager? = null,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
    private val searchHistoryDao: SearchHistoryDao? = null,
    private val scope: CoroutineScope? = null,
) : ChatSearchRepository {
    override val searchHistory: Flow<List<ChatModel>> = searchHistoryDao?.getSearchHistory()?.mapLatest { entities ->
        val chatsById = dialogRepository.getDialogs(accountId)
            .mapNotNull { it.toChatModel() }
            .associateBy { it.id }
        entities.mapNotNull { chatsById[it.chatId] }
    } ?: flow {
        throw UnsupportedOperationException("MTProto search history persistence is not available")
    }

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
        return (result.myResults + result.results)
            .distinct()
            .mapNotNull { it.toChatModel(scope, users, chats) }
    }

    override suspend fun searchMessages(query: String, offset: String, limit: Int): SearchMessagesResult {
        require(limit in 1..MAX_SEARCH_PAGE_SIZE) { "Search page size must be between 1 and $MAX_SEARCH_PAGE_SIZE" }
        val normalized = query.trim()
        if (normalized.isEmpty()) return SearchMessagesResult(emptyList(), "")
        val localStart = when {
            offset.isEmpty() -> 0
            offset.contains(':') -> null
            else -> offset.toIntOrNull()?.takeIf { it >= 0 }
                ?: throw IllegalArgumentException("MTProto message search offset is invalid")
        }
        val store = messageStore ?: unsupported("MTProto message search is not available")
        val config = configSource?.createForAccount(accountId)
            ?: unsupported("MTProto message search is not available")
        val scope = MtProtoAuthKeyScope(accountId, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val factory = transportFactory
        val users = userStore
        val chats = chatStore
        val stager = resultStager
        if (factory != null && users != null && chats != null && stager != null) {
            val cursor = SearchCursor.parse(offset)
            val transport = factory.open(accountId)
            val result = try {
                transport.execute(
                    SearchGlobal(
                        broadcastsOnly = false,
                        groupsOnly = false,
                        usersOnly = false,
                        folderId = null,
                        q = normalized,
                        filter = InputMessagesFilterEmpty,
                        minDate = 0,
                        maxDate = 0,
                        offsetRate = cursor.rate,
                        offsetPeer = cursor.peer?.toInputPeer(scope, users, chats) ?: InputPeerEmpty,
                        offsetId = cursor.messageId,
                        limit = limit,
                    ),
                )
            } finally {
                transport.close()
            }
            val staged = stager.stage(scope, result)
            val slice = result as? MessagesSlice
            val next = slice?.nextRate?.let { rate ->
                staged.lastOrNull()?.peer()?.let { peer ->
                    SearchCursor(
                        rate = rate,
                        peer = SearchCursor.PeerCursor(peer.toChatId(scope, users, chats)),
                        messageId = staged.last().messageId(),
                    ).encode()
                }
            }.orEmpty()
            return SearchMessagesResult(
                staged.mapNotNull { message -> message.peer()?.let { peer ->
                    store.get(scope, peer.toMessagePeerType(), peer.peerId(), message.messageId())?.toDomain()
                } },
                next,
            )
        }
        val start = requireNotNull(localStart) { "MTProto message search offset is invalid" }
        val rows = store.search(scope, normalized, limit + 1, start)
        val page = rows.take(limit)
        val nextOffset = if (rows.size > limit) (start + limit).toString() else ""
        return SearchMessagesResult(page.map { it.toDomain() }, nextOffset)
    }

    override fun addSearchChatId(chatId: Long) {
        val dao = searchHistoryDao ?: unsupported("MTProto search history is not available")
        scope?.launch { dao.insertSearchChatId(SearchHistoryEntity(chatId)) }
            ?: unsupported("MTProto search history scope is not available")
    }

    override fun removeSearchChatId(chatId: Long) {
        val dao = searchHistoryDao ?: unsupported("MTProto search history is not available")
        scope?.launch { dao.deleteSearchChatId(chatId) }
            ?: unsupported("MTProto search history scope is not available")
    }

    override fun clearSearchHistory() {
        val dao = searchHistoryDao ?: unsupported("MTProto search history is not available")
        scope?.launch { dao.clearAll() }
            ?: unsupported("MTProto search history scope is not available")
    }

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

    private fun Message_73e57f95e4.messageId(): Int = when (this) {
        is MessageEmpty -> id
        is MessageService -> id
        is Message_7b7ecf54a3 -> id
    }

    private fun Message_73e57f95e4.peer(): Peer? = when (this) {        is MessageEmpty -> peerId
        is MessageService -> peerId
        is Message_7b7ecf54a3 -> peerId
    }

    private suspend fun Peer.toChatId(
        scope: MtProtoAuthKeyScope,
        users: MtProtoUserProjectionStore,
        chats: MtProtoChatProjectionStore,
    ) = when (this) {
        is PeerUser -> TelegramPeerChatId.encode(DialogPeerType.PRIVATE, userId)
        is PeerChat -> TelegramPeerChatId.encode(DialogPeerType.BASIC_GROUP, chatId)
        is PeerChannel -> TelegramPeerChatId.encode(
            if (chats.get(scope, channelId)?.type == MtProtoChatType.CHANNEL) DialogPeerType.CHANNEL else DialogPeerType.SUPERGROUP,
            channelId,
        )
    }

    private suspend fun SearchCursor.PeerCursor.toInputPeer(
        scope: MtProtoAuthKeyScope,
        users: MtProtoUserProjectionStore,
        chats: MtProtoChatProjectionStore,
    ): InputPeer {
        val peer = TelegramPeerChatId.decode(chatId, isChannel = false)
        return when (peer.type) {
            DialogPeerType.PRIVATE -> {
                val user = requireNotNull(users.get(scope, peer.id)) { "Missing MTProto user projection: ${peer.id}" }
                InputPeerUser(peer.id, requireNotNull(user.accessHash) { "Missing MTProto user access hash: ${peer.id}" })
            }
            DialogPeerType.BASIC_GROUP -> InputPeerChat(peer.id)
            DialogPeerType.SUPERGROUP, DialogPeerType.CHANNEL -> {
                val chat = requireNotNull(chats.get(scope, peer.id)) { "Missing MTProto chat projection: ${peer.id}" }
                InputPeerChannel(peer.id, requireNotNull(chat.accessHash) { "Missing MTProto chat access hash: ${peer.id}" })
            }
            DialogPeerType.UNKNOWN -> error("Cannot search an unknown peer")
        }
    }

    private fun Peer.peerId() = when (this) {
        is PeerUser -> userId
        is PeerChat -> chatId
        is PeerChannel -> channelId
    }

    private fun Peer.toMessagePeerType() = when (this) {        is PeerUser -> MtProtoMessagePeerType.USER
        is PeerChat -> MtProtoMessagePeerType.GROUP
        is PeerChannel -> MtProtoMessagePeerType.CHANNEL
    }

    private data class SearchCursor(
        val rate: Int,
        val peer: PeerCursor?,
        val messageId: Int,
    ) {
        data class PeerCursor(val chatId: Long)

        fun encode() = "${rate}:${requireNotNull(peer).chatId}:${messageId}"

        companion object {
            fun parse(value: String): SearchCursor {
                if (value.isEmpty()) return SearchCursor(0, null, 0)
                val parts = value.split(':')
                require(parts.size == 3) { "MTProto message search offset is invalid" }
                val rate = parts[0].toIntOrNull() ?: error("MTProto message search offset is invalid")
                val chatId = parts[1].toLongOrNull()?.takeIf { it != 0L } ?: error("MTProto message search offset is invalid")
                val messageId = parts[2].toIntOrNull()?.takeIf { it > 0 } ?: error("MTProto message search offset is invalid")
                return SearchCursor(rate, PeerCursor(chatId), messageId)
            }
        }
    }

    private fun MtProtoMessagePeerType.toDialogPeerType() = when (this) {        MtProtoMessagePeerType.USER -> DialogPeerType.PRIVATE
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
