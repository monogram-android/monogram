package org.monogram.data.chats

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import org.monogram.core.DispatcherProvider
import org.monogram.data.datasource.cache.ChatLocalDataSource
import org.monogram.data.datasource.remote.ChatRemoteSource
import org.monogram.data.db.model.TopicEntity
import org.monogram.data.mapper.ChatMapper
import org.monogram.domain.models.TopicModel

class ForumTopicsManager(
    private val chatRemoteSource: ChatRemoteSource,
    private val chatMapper: ChatMapper,
    private val cache: ChatCache,
    private val fileManager: ChatFileManager,
    private val chatLocalDataSource: ChatLocalDataSource,
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope,
    private val fetchUser: (Long) -> Unit
) {
    private val _forumTopicsFlow = MutableSharedFlow<Pair<Long, List<TopicModel>>>(
        replay = 1,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val forumTopicsFlow: Flow<Pair<Long, List<TopicModel>>> = _forumTopicsFlow.asSharedFlow()

    private var activeForumChatId: Long? = null

    fun refreshActiveForumTopics() {
        val chatId = activeForumChatId ?: return
        scope.launch { getForumTopics(chatId) }
    }

    suspend fun getForumTopics(
        chatId: Long,
        query: String = "",
        offsetDate: Int = 0,
        offsetMessageId: Long = 0,
        offsetForumTopicId: Int = 0,
        limit: Int = 20
    ): List<TopicModel> {
        activeForumChatId = chatId
        val result = chatRemoteSource.getForumTopics(
            chatId = chatId,
            query = query,
            offsetDate = offsetDate,
            offsetMessageId = offsetMessageId,
            offsetForumTopicId = offsetForumTopicId,
            limit = limit
        ) ?: return emptyList()

        val models = result.topics.map { topic ->
            val (lastMessageText, entities, time) = chatMapper.formatMessageInfo(topic.lastMessage, null) { userId ->
                cache.usersCache[userId]?.firstName ?: run {
                    fetchUser(userId)
                    null
                }
            }

            val emojiId = topic.info.icon.customEmojiId
            var emojiPath: String? = null
            if (emojiId != 0L) {
                emojiPath = fileManager.getEmojiPath(emojiId)
                if (emojiPath == null) {
                    fileManager.loadEmoji(emojiId)
                }
            }

            var senderName: String? = null
            var senderAvatar: String? = null
            when (val senderId = topic.lastMessage?.senderId) {
                is TdApi.MessageSenderUser -> {
                    cache.usersCache[senderId.userId]?.let { user ->
                        senderName = user.firstName
                        user.profilePhoto?.small?.let { small ->
                            fileManager.registerTrackedFile(small.id)
                            senderAvatar = small.local.path.ifEmpty { fileManager.getFilePath(small.id) }
                            if (senderAvatar.isNullOrEmpty()) {
                                fileManager.downloadFile(small.id, 24, synchronous = false)
                            }
                        }
                    } ?: fetchUser(senderId.userId)
                }

                is TdApi.MessageSenderChat -> {
                    cache.getChat(senderId.chatId)?.let { chat ->
                        senderName = chat.title
                        chat.photo?.small?.let { small ->
                            fileManager.registerTrackedFile(small.id)
                            senderAvatar = small.local.path.ifEmpty { fileManager.getFilePath(small.id) }
                            if (senderAvatar.isNullOrEmpty()) {
                                fileManager.downloadFile(small.id, 24, synchronous = false)
                            }
                        }
                    }
                }

                else -> Unit
            }

            TopicModel(
                id = topic.info.forumTopicId,
                name = topic.info.name,
                iconCustomEmojiId = emojiId,
                iconCustomEmojiPath = emojiPath,
                iconColor = topic.info.icon.color,
                isClosed = topic.info.isClosed,
                isPinned = topic.isPinned,
                unreadCount = topic.unreadCount,
                lastMessageText = lastMessageText,
                lastMessageEntities = entities,
                lastMessageTime = time,
                order = topic.order,
                lastMessageSenderName = senderName,
                lastMessageSenderAvatar = senderAvatar
            )
        }

        scope.launch(dispatchers.io) {
            chatLocalDataSource.insertTopics(result.topics.map { topic ->
                val (text, _, time) = chatMapper.formatMessageInfo(topic.lastMessage, null) { null }
                TopicEntity(
                    chatId = chatId,
                    id = topic.info.forumTopicId,
                    name = topic.info.name,
                    iconCustomEmojiId = topic.info.icon.customEmojiId,
                    iconColor = topic.info.icon.color,
                    isClosed = topic.info.isClosed,
                    isPinned = topic.isPinned,
                    unreadCount = topic.unreadCount,
                    lastMessageText = text,
                    lastMessageTime = time,
                    order = topic.order,
                    lastMessageSenderName = null
                )
            })
        }

        _forumTopicsFlow.tryEmit(chatId to models)
        return models
    }
}