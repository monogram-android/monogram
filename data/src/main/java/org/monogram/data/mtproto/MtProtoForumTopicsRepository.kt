package org.monogram.data.mtproto

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.models.TopicModel
import org.monogram.domain.repository.ForumTopicsRepository
import org.monogram.mtproto.tl.generated.cloud.layer223.ForumTopicDeleted
import org.monogram.mtproto.tl.generated.cloud.layer223.ForumTopic_63cf4e6dc9
import org.monogram.mtproto.tl.generated.cloud.layer223.ForumTopic_cfa9d73abd
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeer
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageService
import org.monogram.mtproto.tl.generated.cloud.layer223.Message_7b7ecf54a3
import org.monogram.mtproto.tl.generated.cloud.layer223.Message_73e57f95e4
import org.monogram.mtproto.tl.generated.cloud.layer223.Peer
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.CreateForumTopic
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.DeleteTopicHistory
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.EditForumTopic
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetForumTopics
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetForumTopicsById
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.ReadMentions
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.ReadReactions
import org.monogram.mtproto.transport.MtProtoRpcTransport

/**
 * Real MTProto forum-topic support.
 *
 * Pagination mirrors upstream TopicsController (td/telegram/messenger/TopicsController.java):
 * messages.getForumTopics with offset_date / offset_id / offset_topic and limit <= 100.
 */
internal class MtProtoForumTopicsRepository(
    private val configSource: TelegramMtProtoBootstrapConfigSource? = null,
    private val transportFactory: MtProtoSessionTransportFactory? = null,
    private val userStore: MtProtoUserProjectionStore? = null,
    private val chatStore: MtProtoChatProjectionStore? = null,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : ForumTopicsRepository {

    private val _forumTopicsFlow = MutableStateFlow(0L to emptyList<TopicModel>())

    override val forumTopicsFlow: Flow<Pair<Long, List<TopicModel>>>
        get() = _forumTopicsFlow.asStateFlow()

    override suspend fun getForumTopics(
        chatId: Long,
        query: String,
        offsetDate: Int,
        offsetMessageId: Long,
        offsetForumTopicId: Int,
        limit: Int,
    ): List<TopicModel> {
        require(limit in 1..MAX_PAGE_SIZE) { "MTProto forum topics page size must be between 1 and $MAX_PAGE_SIZE" }
        val result = execute("MTProto forum topics are not available") { transport, scope ->
            transport.execute(
                GetForumTopics(
                    peer = resolveInputPeer(scope, chatId),
                    q = query.trim().takeIf(String::isNotEmpty),
                    offsetDate = offsetDate,
                    offsetId = offsetMessageId.toMtProtoMessageId(),
                    offsetTopic = offsetForumTopicId,
                    limit = limit,
                ),
            )
        }.asForumTopics()
        stageProjections(result.users, result.chats)
        val messagesById = result.messages.mapNotNull { it.asTextMessageOrNull() }.associateBy(Message_7b7ecf54a3::id)
        val topics = mapTopics(result.topics, messagesById)
        _forumTopicsFlow.value = chatId to topics
        return topics
    }

    /**
     * Marks a forum topic as read on the server. Topic scoping uses inputNotifyForumTopic-scoped
     * methods (messages.readMentions / messages.readReactions); layer 223's messages.readHistory
     * has no top_msg_id flag, so per-topic message read state relies on server-side updates.
     */
    override suspend fun markForumTopicAsRead(chatId: Long, topicId: Int) {
        execute("MTProto forum topic read marking is not available") { transport, scope ->
            val peer = resolveInputPeer(scope, chatId)
            transport.execute(ReadMentions(peer = peer, topMsgId = topicId))
            transport.execute(ReadReactions(peer = peer, topMsgId = topicId, savedPeerId = null))
        }
    }

    /** Mirrors upstream TopicCreateFragment.createForumTopic (random_id + optional icon flags). */
    suspend fun createForumTopic(
        chatId: Long,
        title: String,
        iconColor: Int? = null,
        iconEmojiId: Long? = null,
    ) {
        require(title.isNotBlank()) { "Forum topic title must not be blank" }
        execute("MTProto forum topic creation is not available") { transport, scope ->
            transport.execute(
                CreateForumTopic(
                    titleMissing = false,
                    peer = resolveInputPeer(scope, chatId),
                    title = title.trim(),
                    iconColor = iconColor,
                    iconEmojiId = iconEmojiId,
                    randomId = Random.nextLong(),
                    sendAs = null,
                ),
            )
        }
    }

    /**
     * Mirrors upstream TopicsController.editForumTopic:
     * flags 1=title, 2=icon_emoji_id, 4=closed, 8=hidden.
     */
    suspend fun editForumTopic(
        chatId: Long,
        topicId: Int,
        title: String? = null,
        iconEmojiId: Long? = null,
        isClosed: Boolean? = null,
        isHidden: Boolean? = null,
    ) {
        require(title == null || title.isNotBlank()) { "Forum topic title must not be blank" }
        execute("MTProto forum topic editing is not available") { transport, scope ->
            transport.execute(
                EditForumTopic(
                    peer = resolveInputPeer(scope, chatId),
                    topicId = topicId,
                    title = title?.trim(),
                    iconEmojiId = iconEmojiId,
                    closed = isClosed,
                    hidden = isHidden,
                ),
            )
        }
    }

    /** Mirrors upstream TopicsController.deleteTopicHistory via messages.deleteTopicHistory. */
    suspend fun deleteTopicHistory(chatId: Long, topicId: Int) {
        execute("MTProto forum topic deletion is not available") { transport, scope ->
            transport.execute(
                DeleteTopicHistory(
                    peer = resolveInputPeer(scope, chatId),
                    topMsgId = topicId,
                ),
            )
        }
    }

    /** Resolves topics by ids via messages.getForumTopicsByID (upstream TopicsController cache fill). */
    suspend fun getForumTopicsById(chatId: Long, topicIds: List<Int>): List<TopicModel> {
        if (topicIds.isEmpty()) return emptyList()
        val result = execute("MTProto forum topics are not available") { transport, scope ->
            transport.execute(
                GetForumTopicsById(
                    peer = resolveInputPeer(scope, chatId),
                    topics = topicIds,
                ),
            )
        }.asForumTopics()
        stageProjections(result.users, result.chats)
        val messagesById = result.messages.mapNotNull { it.asTextMessageOrNull() }.associateBy(Message_7b7ecf54a3::id)
        return mapTopics(result.topics, messagesById)
    }

    private fun org.monogram.mtproto.tl.generated.cloud.layer223.messages.ForumTopics_0fda3ff86b.asForumTopics() =
        this as? org.monogram.mtproto.tl.generated.cloud.layer223.messages.ForumTopics_952a2dfb9e
            ?: error("Unsupported messages.forumTopics result")

    private suspend fun <R> execute(
        unavailableMessage: String,
        block: suspend (MtProtoRpcTransport, MtProtoAuthKeyScope) -> R,
    ): R {
        val factory = transportFactory ?: unsupported(unavailableMessage)
        val config = configSource?.createForAccount(accountId) ?: unsupported(unavailableMessage)
        val scope = MtProtoAuthKeyScope(accountId, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val transport = factory.open(accountId)
        return try {
            block(transport, scope)
        } finally {
            transport.close()
        }
    }

    private suspend fun stageProjections(
        users: List<org.monogram.mtproto.tl.generated.cloud.layer223.User_655b5dfc57>,
        chats: List<org.monogram.mtproto.tl.generated.cloud.layer223.Chat_7fdd7beb6e>,
    ) {
        val config = configSource?.createForAccount(accountId) ?: return
        val scope = MtProtoAuthKeyScope(accountId, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        userStore?.upsert(scope, users)
        chatStore?.upsert(scope, chats)
    }

    private suspend fun resolveInputPeer(scope: MtProtoAuthKeyScope, chatId: Long): InputPeer {
        val store = chatStore ?: unsupported("MTProto forum topics are not available")
        val peer = TelegramPeerChatId.decode(chatId)
        return when (peer.type) {
            DialogPeerType.BASIC_GROUP -> InputPeerChat(peer.id)
            DialogPeerType.SUPERGROUP, DialogPeerType.CHANNEL -> {
                val chat = requireNotNull(store.get(scope, peer.id)) { "Missing MTProto chat projection: ${peer.id}" }
                InputPeerChannel(peer.id, requireNotNull(chat.accessHash) { "Missing MTProto chat access hash: ${peer.id}" })
            }
            DialogPeerType.PRIVATE, DialogPeerType.UNKNOWN ->
                error("Forum topics are only supported for groups and channels")
        }
    }

    private suspend fun mapTopics(
        topics: List<ForumTopic_63cf4e6dc9>,
        messagesById: Map<Int, Message_7b7ecf54a3>,
    ): List<TopicModel> {
        val config = configSource?.createForAccount(accountId)
            ?: unsupported("MTProto forum topics are not available")
        val scope = MtProtoAuthKeyScope(accountId, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        return topics.mapNotNull { topic -> topic.toDomainModel(scope, messagesById) }
    }

    private suspend fun ForumTopic_63cf4e6dc9.toDomainModel(
        scope: MtProtoAuthKeyScope,
        messagesById: Map<Int, Message_7b7ecf54a3>,
    ): TopicModel? = when (this) {
        is ForumTopicDeleted -> null
        is ForumTopic_cfa9d73abd -> {
            val lastMessage = messagesById[topMessage]
            TopicModel(
                id = id,
                name = title,
                iconCustomEmojiId = iconEmojiId ?: 0L,
                iconColor = iconColor,
                isClosed = closed || hidden,
                isPinned = pinned,
                unreadCount = unreadCount,
                lastReadInboxMessageId = readInboxMaxId.toLong(),
                lastReadOutboxMessageId = readOutboxMaxId.toLong(),
                unreadMentionCount = unreadMentionsCount,
                unreadReactionCount = unreadReactionsCount,
                order = topMessage.toLong(),
                lastMessageText = lastMessage?.message.orEmpty(),
                lastMessageTime = lastMessage?.date?.toDisplayTime().orEmpty(),
                lastMessageSenderName = fromId?.senderDisplayName(scope),
            )
        }
    }

    private suspend fun Peer.senderDisplayName(scope: MtProtoAuthKeyScope): String? = when (this) {
        is PeerUser -> {
            val user = userStore?.get(scope, userId)
            listOfNotNull(user?.firstName, user?.lastName).joinToString(" ").ifBlank { user?.username }
        }
        is PeerChat -> chatStore?.get(scope, chatId)?.title
        is PeerChannel -> chatStore?.get(scope, channelId)?.title
    }

    private fun Message_73e57f95e4.asTextMessageOrNull(): Message_7b7ecf54a3? = when (this) {
        is Message_7b7ecf54a3 -> this
        is MessageEmpty, is MessageService -> null
    }

    private fun Int.toDisplayTime(): String {
        val now = Calendar.getInstance(Locale.getDefault())
        val then = Calendar.getInstance(Locale.getDefault()).apply { timeInMillis = toLong() * 1000L }
        val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        val format = if (sameDay) SimpleDateFormat("HH:mm", Locale.getDefault()) else SimpleDateFormat("dd MMM", Locale.getDefault())
        return format.format(Date(toLong() * 1000L))
    }

    private fun Long.toMtProtoMessageId(): Int {
        require(this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            "MTProto forum topics cursor message id is out of range"
        }
        return toInt()
    }

    private fun unsupported(message: String): Nothing = throw UnsupportedOperationException(message)

    private companion object {
        const val MAX_PAGE_SIZE = 100

        const val DEFAULT_ACCOUNT_ID = "default"
    }
}
