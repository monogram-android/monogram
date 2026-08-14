package org.monogram.data.datasource.cache

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.monogram.data.db.model.ChatEntity
import org.monogram.data.db.model.ChatFullInfoEntity
import org.monogram.data.db.model.MessageEntity
import org.monogram.data.db.model.TopicEntity
import org.monogram.data.db.model.MessageWindowEntity
import java.util.concurrent.ConcurrentHashMap

class InMemoryChatLocalDataSource : ChatLocalDataSource {
    private val chats = MutableStateFlow<Map<Long, ChatEntity>>(emptyMap())
    private val messages = ConcurrentHashMap<Long, MutableStateFlow<Map<Long, MessageEntity>>>()
    private val fullInfos = ConcurrentHashMap<Long, ChatFullInfoEntity>()
    private val topics = ConcurrentHashMap<Long, MutableStateFlow<Map<Int, TopicEntity>>>()
    private val windows = ConcurrentHashMap<Triple<Long, String, Long>, MessageWindowEntity>()

    override fun getAllChats(): Flow<List<ChatEntity>> =
        chats.map {
            it.values.sortedWith(
                compareByDescending<ChatEntity> { chat -> chat.isPinned }
                    .thenByDescending { chat -> chat.order }
            )
        }

    override suspend fun getTopChats(limit: Int): List<ChatEntity> {
        return chats.value.values
            .sortedWith(
                compareByDescending<ChatEntity> { chat -> chat.isPinned }
                    .thenByDescending { chat -> chat.order }
            )
            .take(limit)
    }

    override suspend fun getStartupChats(limit: Int): List<ChatEntity> {
        return chats.value.values
            .asSequence()
            .filter { it.order != 0L }
            .sortedWith(
                compareByDescending<ChatEntity> { chat -> chat.isPinned }
                    .thenByDescending { chat -> chat.order }
            )
            .take(limit)
            .toList()
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
        windows.clear()
    }

    override fun getMessagesForChat(chatId: Long): Flow<List<MessageEntity>> =
        messages.getOrPut(chatId) { MutableStateFlow(emptyMap()) }
            .map { it.values.sortedWith(compareByDescending<MessageEntity> { msg -> msg.date }.thenByDescending { it.id }) }

    override suspend fun getMessagesOlder(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
        threadId: Long?
    ): List<MessageEntity> {
        val chatMessages = scopedMessages(chatId, threadId)
        return chatMessages.filter { it.id < fromMessageId }
            .sortedWith(compareByDescending<MessageEntity> { it.date }.thenByDescending { it.id })
            .take(limit)
    }

    override suspend fun getMessagesNewer(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
        threadId: Long?
    ): List<MessageEntity> {
        val chatMessages = scopedMessages(chatId, threadId)
        return chatMessages.filter { it.id > fromMessageId }
            .sortedWith(compareBy<MessageEntity> { it.date }.thenBy { it.id })
            .take(limit)
    }

    override suspend fun getMessagesAround(
        chatId: Long,
        messageId: Long,
        limit: Int,
        threadId: Long?
    ): List<MessageEntity> {
        val chatMessages = scopedMessages(chatId, threadId)
        val olderLimit = (limit + 1) / 2
        val newerLimit = limit - olderLimit
        val olderAndTarget = chatMessages.filter { it.id <= messageId }
            .sortedWith(compareByDescending<MessageEntity> { it.date }.thenByDescending { it.id })
            .take(olderLimit)
        val newer = chatMessages.filter { it.id > messageId }
            .sortedWith(compareBy<MessageEntity> { it.date }.thenBy { it.id })
            .take(newerLimit)
        return (olderAndTarget + newer).distinctBy { it.id }
            .sortedWith(compareByDescending<MessageEntity> { it.date }.thenByDescending { it.id })
    }

    override suspend fun getLatestMessages(
        chatId: Long,
        limit: Int,
        threadId: Long?
    ): List<MessageEntity> {
        val chatMessages = scopedMessages(chatId, threadId)
        return chatMessages.sortedWith(compareByDescending<MessageEntity> { it.date }.thenByDescending { it.id })
            .take(limit)
    }

    override suspend fun getMessagesByIds(
        chatId: Long,
        messageIds: List<Long>
    ): List<MessageEntity> {
        val chatMessages = messages[chatId]?.value ?: return emptyList()
        return messageIds.mapNotNull { chatMessages[it] }
    }

    override suspend fun getMessageWindow(
        chatId: Long,
        scopeType: String,
        scopeId: Long
    ): MessageWindowEntity? =
        windows[Triple(chatId, scopeType, scopeId)]

    override suspend fun upsertMessageWindow(window: MessageWindowEntity) {
        windows[Triple(window.chatId, window.scopeType, window.scopeId)] = window
    }

    override suspend fun deleteMessageWindow(chatId: Long, scopeType: String, scopeId: Long) {
        windows.remove(Triple(chatId, scopeType, scopeId))
    }

    override suspend fun deleteMessageWindowsForChat(chatId: Long) {
        windows.keys.removeAll { it.first == chatId }
    }

    override suspend fun invalidateMessageWindowCoverageForChat(chatId: Long) {
        windows.replaceAll { key, window ->
            if (key.first == chatId) {
                window.copy(
                    oldestMessageId = null,
                    newestMessageId = null,
                    olderBoundaryReached = false,
                    newerBoundaryReached = false
                )
            } else {
                window
            }
        }
    }

    override suspend fun insertMessage(message: MessageEntity) {
        messages.getOrPut(message.chatId) { MutableStateFlow(emptyMap()) }
            .update { it + (message.id to message) }
    }

    override suspend fun insertMessages(messages: List<MessageEntity>) {
        messages.forEach { insertMessage(it) }
    }

    override suspend fun persistHistoryMessages(writes: List<MessageCacheMutation.HistoryWrite>) {
        writes.groupBy { it.message.chatId }.forEach { (chatId, chatWrites) ->
            messages.getOrPut(chatId) { MutableStateFlow(emptyMap()) }
                .update { current ->
                    chatWrites.fold(current) { result, write ->
                        if (result[write.message.id] == write.expectedExisting) {
                            result + (write.message.id to write.message)
                        } else {
                            result
                        }
                    }
                }
        }
    }

    override suspend fun replaceMessage(message: MessageEntity) {
        insertMessage(message)
    }

    override suspend fun replaceMessageId(
        chatId: Long,
        oldMessageId: Long,
        message: MessageEntity
    ) {
        messages.getOrPut(chatId) { MutableStateFlow(emptyMap()) }
            .update { it - oldMessageId + (message.id to message) }
    }

    override suspend fun markAsRead(chatId: Long, upToMessageId: Long) {
        messages[chatId]?.update { current ->
            current.mapValues { (_, msg) ->
                if (msg.id <= upToMessageId && !msg.isRead) msg.copy(isRead = true) else msg
            }
        }
    }

    override suspend fun updateMessageContent(
        chatId: Long,
        messageId: Long,
        content: String,
        contentType: String,
        contentMeta: String?,
        mediaFileId: Int,
        mediaPath: String?,
        editDate: Int
    ) {
        val flow = messages[chatId] ?: return
        val current = flow.value[messageId] ?: return
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

    override suspend fun updateMediaPath(chatId: Long, messageId: Long, fileId: Int, path: String) {
        val flow = messages[chatId] ?: return
        val current = flow.value[messageId] ?: return
        if (current.mediaFileId != fileId || fileId == 0) return
        flow.update {
            it + (messageId to current.copy(mediaPath = path))
        }
    }

    override suspend fun clearCachedMediaPaths() {
        val mediaTypes = setOf("photo", "video", "video_note", "document", "gif", "voice", "sticker", "audio")
        messages.values.forEach { flow ->
            flow.update { current ->
                current.mapValues { (_, message) ->
                    if (
                        message.contentType in mediaTypes &&
                        (message.mediaFileId != 0 || message.mediaPath != null || message.mediaThumbnailPath != null)
                    ) {
                        message.copy(
                            mediaFileId = 0,
                            mediaPath = null,
                            mediaThumbnailPath = null
                        )
                    } else {
                        message
                    }
                }
            }
        }
    }

    override suspend fun clearCachedChatAvatarPaths() {
        chats.update { current ->
            current.mapValues { (_, chat) ->
                if (chat.avatarPath != null) {
                    chat.copy(avatarPath = null)
                } else {
                    chat
                }
            }
        }
    }

    override suspend fun updateInteractionInfo(
        chatId: Long,
        messageId: Long,
        viewCount: Int,
        forwardCount: Int,
        replyCount: Int
    ) {
        val flow = messages[chatId] ?: return
        val current = flow.value[messageId] ?: return
        flow.update {
            it + (messageId to current.copy(
                viewCount = viewCount,
                forwardCount = forwardCount,
                replyCount = replyCount
            ))
        }
    }

    override suspend fun deleteMessage(chatId: Long, messageId: Long) {
        messages[chatId]?.let { flow ->
            if (flow.value.containsKey(messageId)) {
                flow.update { it - messageId }
            }
        }
        windows.keys.removeAll { it.first == chatId }
    }

    override suspend fun clearMessagesForChat(chatId: Long) {
        messages[chatId]?.value = emptyMap()
        windows.keys.removeAll { it.first == chatId }
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
        messages.values.forEach { flow ->
            flow.update { it.filterValues { msg -> msg.createdAt >= timestamp } }
        }
        windows.clear()
    }

    private fun scopedMessages(chatId: Long, threadId: Long?): List<MessageEntity> {
        val chatMessages = messages[chatId]?.value?.values ?: return emptyList()
        return chatMessages.filter { message -> message.threadId == threadId }
    }
}
