package org.monogram.data.datasource.cache

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.monogram.data.db.model.ChatEntity
import org.monogram.data.db.model.ChatFullInfoEntity
import org.monogram.data.db.model.MessageEntity
import org.monogram.data.db.model.TopicEntity
import java.util.concurrent.ConcurrentHashMap

class InMemoryChatLocalDataSource : ChatLocalDataSource {
    private val chats = MutableStateFlow<Map<Long, ChatEntity>>(emptyMap())
    private val messages = ConcurrentHashMap<Long, MutableStateFlow<Map<Long, MessageEntity>>>()
    private val fullInfos = ConcurrentHashMap<Long, ChatFullInfoEntity>()
    private val topics = ConcurrentHashMap<Long, MutableStateFlow<Map<Int, TopicEntity>>>()

    override fun getAllChats(): Flow<List<ChatEntity>> =
        chats.map {
            it.values.sortedWith(
                compareByDescending<ChatEntity> { chat -> chat.isPinned }
                    .thenByDescending { chat -> chat.order }
            )
        }

    override suspend fun getChat(chatId: Long): ChatEntity? = chats.value[chatId]

    override suspend fun insertChat(chat: ChatEntity) {
        chats.update { it + (chat.id to chat) }
    }

    override suspend fun insertChats(chats: List<ChatEntity>) {
        this.chats.update { it + chats.associateBy { chat -> chat.id } }
    }

    override suspend fun deleteChat(chatId: Long) {
        chats.update { it - chatId }
    }

    override suspend fun clearAllChats() {
        chats.value = emptyMap()
    }

    override suspend fun clearAll() {
        chats.value = emptyMap()
        messages.clear()
        fullInfos.clear()
        topics.clear()
    }

    override fun getMessagesForChat(chatId: Long): Flow<List<MessageEntity>> =
        messages.getOrPut(chatId) { MutableStateFlow(emptyMap()) }
            .map { it.values.sortedByDescending { msg -> msg.date } }

    override suspend fun getMessagesOlder(chatId: Long, fromMessageId: Long, limit: Int): List<MessageEntity> {
        val chatMessages = messages[chatId]?.value?.values ?: return emptyList()
        return chatMessages.filter { it.id < fromMessageId }
            .sortedByDescending { it.date }
            .take(limit)
    }

    override suspend fun getMessagesNewer(chatId: Long, fromMessageId: Long, limit: Int): List<MessageEntity> {
        val chatMessages = messages[chatId]?.value?.values ?: return emptyList()
        return chatMessages.filter { it.id > fromMessageId }
            .sortedBy { it.date }
            .take(limit)
    }

    override suspend fun getLatestMessages(chatId: Long, limit: Int): List<MessageEntity> {
        val chatMessages = messages[chatId]?.value?.values ?: return emptyList()
        return chatMessages.sortedByDescending { it.date }.take(limit)
    }

    override suspend fun insertMessage(message: MessageEntity) {
        messages.getOrPut(message.chatId) { MutableStateFlow(emptyMap()) }
            .update { it + (message.id to message) }
    }

    override suspend fun insertMessages(messages: List<MessageEntity>) {
        messages.forEach { insertMessage(it) }
    }

    override suspend fun markAsRead(chatId: Long, upToMessageId: Long) {
        messages[chatId]?.update { current ->
            current.mapValues { (_, msg) ->
                if (msg.id <= upToMessageId && !msg.isRead) msg.copy(isRead = true) else msg
            }
        }
    }

    override suspend fun updateMessageContent(
        messageId: Long,
        content: String,
        contentType: String,
        contentMeta: String?,
        mediaFileId: Int,
        mediaPath: String?,
        editDate: Int
    ) {
        messages.values.forEach { flow ->
            val current = flow.value[messageId] ?: return@forEach
            flow.update {
                it + (messageId to current.copy(
                    content = content,
                    contentType = contentType,
                    contentMeta = contentMeta,
                    mediaFileId = mediaFileId,
                    mediaPath = mediaPath,
                    editDate = editDate
                ))
            }
        }
    }

    override suspend fun updateMediaPath(fileId: Int, path: String) {
        messages.values.forEach { flow ->
            flow.update { current ->
                current.mapValues { (_, message) ->
                    if (message.mediaFileId == fileId) {
                        message.copy(mediaPath = path)
                    } else {
                        message
                    }
                }
            }
        }
    }

    override suspend fun updateInteractionInfo(messageId: Long, viewCount: Int, forwardCount: Int, replyCount: Int) {
        messages.values.forEach { flow ->
            val current = flow.value[messageId] ?: return@forEach
            flow.update {
                it + (messageId to current.copy(viewCount = viewCount, forwardCount = forwardCount, replyCount = replyCount))
            }
        }
    }

    override suspend fun deleteMessage(messageId: Long) {
        messages.values.forEach { flow ->
            if (flow.value.containsKey(messageId)) {
                flow.update { it - messageId }
            }
        }
    }

    override suspend fun clearMessagesForChat(chatId: Long) {
        messages[chatId]?.value = emptyMap()
    }

    override suspend fun getChatFullInfo(chatId: Long): ChatFullInfoEntity? = fullInfos[chatId]

    override suspend fun insertChatFullInfo(info: ChatFullInfoEntity) {
        fullInfos[info.chatId] = info
    }

    override suspend fun deleteChatFullInfo(chatId: Long) {
        fullInfos.remove(chatId)
    }

    override fun getTopicsForChat(chatId: Long): Flow<List<TopicEntity>> =
        topics.getOrPut(chatId) { MutableStateFlow(emptyMap()) }
            .map { it.values.sortedByDescending { topic -> topic.order } }

    override suspend fun insertTopic(topic: TopicEntity) {
        topics.getOrPut(topic.chatId) { MutableStateFlow(emptyMap()) }
            .update { it + (topic.id to topic) }
    }

    override suspend fun insertTopics(topics: List<TopicEntity>) {
        topics.forEach { insertTopic(it) }
    }

    override suspend fun deleteTopic(chatId: Long, topicId: Int) {
        topics[chatId]?.update { it - topicId }
    }

    override suspend fun clearTopicsForChat(chatId: Long) {
        topics[chatId]?.value = emptyMap()
    }

    override suspend fun deleteExpired(timestamp: Long) {
        chats.update { it.filterValues { chat -> chat.createdAt >= timestamp } }
        messages.values.forEach { flow ->
            flow.update { it.filterValues { msg -> msg.createdAt >= timestamp } }
        }
        fullInfos.values.removeIf { it.createdAt < timestamp }
        topics.values.forEach { flow ->
            flow.update { it.filterValues { topic -> topic.createdAt >= timestamp } }
        }
    }
}
