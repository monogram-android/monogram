package org.monogram.data.datasource.cache

import kotlinx.coroutines.flow.Flow
import org.monogram.data.db.model.ChatEntity
import org.monogram.data.db.model.ChatFullInfoEntity
import org.monogram.data.db.model.MessageEntity
import org.monogram.data.db.model.TopicEntity
import org.monogram.data.db.model.MessageWindowEntity

interface ChatLocalDataSource {
    fun getAllChats(): Flow<List<ChatEntity>>
    suspend fun getTopChats(limit: Int): List<ChatEntity>
    suspend fun getStartupChats(limit: Int): List<ChatEntity>
    suspend fun getChat(chatId: Long): ChatEntity?
    suspend fun insertChat(chat: ChatEntity)
    suspend fun insertChats(chats: List<ChatEntity>)
    suspend fun deleteChat(chatId: Long)
    suspend fun clearAllChats()
    suspend fun clearAll()

    fun getMessagesForChat(chatId: Long): Flow<List<MessageEntity>>
    suspend fun getMessagesOlder(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
        threadId: Long? = null
    ): List<MessageEntity>

    suspend fun getMessagesNewer(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
        threadId: Long? = null
    ): List<MessageEntity>

    suspend fun getMessagesAround(
        chatId: Long,
        messageId: Long,
        limit: Int,
        threadId: Long? = null
    ): List<MessageEntity>

    suspend fun getLatestMessages(
        chatId: Long,
        limit: Int,
        threadId: Long? = null
    ): List<MessageEntity>
    suspend fun getMessagesByIds(chatId: Long, messageIds: List<Long>): List<MessageEntity>
    suspend fun getMessageWindow(
        chatId: Long,
        scopeType: String,
        scopeId: Long
    ): MessageWindowEntity?

    suspend fun upsertMessageWindow(window: MessageWindowEntity)
    suspend fun deleteMessageWindow(chatId: Long, scopeType: String, scopeId: Long)
    suspend fun deleteMessageWindowsForChat(chatId: Long)
    suspend fun invalidateMessageWindowCoverage(chatId: Long, scopeType: String, scopeId: Long) {
        val current = getMessageWindow(chatId, scopeType, scopeId) ?: return
        upsertMessageWindow(current.withInvalidCoverage())
    }

    suspend fun invalidateMessageWindowCoverageForChat(chatId: Long) = Unit
    suspend fun updateProtectedMessageId(
        chatId: Long,
        scopeType: String,
        scopeId: Long,
        messageId: Long?
    ) {
        val current = getMessageWindow(chatId, scopeType, scopeId)
        upsertMessageWindow(
            current?.copy(protectedMessageId = messageId) ?: MessageWindowEntity(
                chatId = chatId,
                scopeType = scopeType,
                scopeId = scopeId,
                oldestMessageId = null,
                newestMessageId = null,
                olderBoundaryReached = false,
                newerBoundaryReached = false,
                lastNetworkSyncAt = 0L,
                generation = 0L,
                protectedMessageId = messageId
            )
        )
    }
    suspend fun insertMessage(message: MessageEntity)
    suspend fun insertMessages(messages: List<MessageEntity>)
    suspend fun persistHistoryMessages(writes: List<MessageCacheMutation.HistoryWrite>) {
        if (writes.isEmpty()) return
        val chatId = writes.first().message.chatId
        val currentById =
            getMessagesByIds(chatId, writes.map { it.message.id }).associateBy { it.id }
        insertMessages(
            writes.mapNotNull { write ->
                write.message.takeIf { currentById[write.message.id] == write.expectedExisting }
            }
        )
    }
    suspend fun replaceMessage(message: MessageEntity)
    suspend fun replaceMessageId(chatId: Long, oldMessageId: Long, message: MessageEntity)
    suspend fun markAsRead(chatId: Long, upToMessageId: Long)
    suspend fun updateMessageContent(
        chatId: Long,
        messageId: Long,
        content: String,
        contentType: String,
        contentMeta: String?,
        mediaFileId: Int,
        mediaPath: String?,
        editDate: Int
    )

    suspend fun updateMediaPath(chatId: Long, messageId: Long, fileId: Int, path: String)
    suspend fun clearCachedMediaPaths()
    suspend fun clearCachedChatAvatarPaths()

    suspend fun updateInteractionInfo(
        chatId: Long,
        messageId: Long,
        viewCount: Int,
        forwardCount: Int,
        replyCount: Int
    )

    suspend fun deleteMessage(chatId: Long, messageId: Long)

    suspend fun deleteMessages(chatId: Long, messageIds: List<Long>) {
        messageIds.forEach { messageId -> deleteMessage(chatId, messageId) }
    }

    suspend fun applyMessageCacheMutations(mutations: List<MessageCacheMutation>) {
        mutations.forEach { mutation ->
            when (mutation) {
                is MessageCacheMutation.Persist -> replaceMessage(mutation.message)
                is MessageCacheMutation.PersistHistoryBatch -> {
                    persistHistoryMessages(mutation.writes)
                    upsertMessageWindow(mutation.window)
                }

                is MessageCacheMutation.UpdateWindow -> upsertMessageWindow(mutation.window)
                is MessageCacheMutation.ReplaceId -> replaceMessageId(
                    mutation.chatId,
                    mutation.oldMessageId,
                    mutation.message
                )

                is MessageCacheMutation.UpdateContent -> updateMessageContent(
                    mutation.chatId,
                    mutation.messageId,
                    mutation.content,
                    mutation.contentType,
                    mutation.contentMeta,
                    mutation.mediaFileId,
                    mutation.mediaPath,
                    mutation.editDate
                )

                is MessageCacheMutation.UpdateInteraction -> updateInteractionInfo(
                    mutation.chatId,
                    mutation.messageId,
                    mutation.viewCount,
                    mutation.forwardCount,
                    mutation.replyCount
                )

                is MessageCacheMutation.MarkRead -> markAsRead(
                    mutation.chatId,
                    mutation.upToMessageId
                )

                is MessageCacheMutation.DeleteMessages -> deleteMessages(
                    mutation.chatId,
                    mutation.messageIds
                )

                is MessageCacheMutation.UpdateMediaPath -> updateMediaPath(
                    mutation.chatId,
                    mutation.messageId,
                    mutation.fileId,
                    mutation.path
                )
            }
        }
    }
    suspend fun clearMessagesForChat(chatId: Long)

    suspend fun getChatFullInfo(chatId: Long): ChatFullInfoEntity?
    suspend fun insertChatFullInfo(info: ChatFullInfoEntity)
    suspend fun deleteChatFullInfo(chatId: Long)

    fun getTopicsForChat(chatId: Long): Flow<List<TopicEntity>>
    suspend fun insertTopic(topic: TopicEntity)
    suspend fun insertTopics(topics: List<TopicEntity>)
    suspend fun deleteTopic(chatId: Long, topicId: Int)
    suspend fun clearTopicsForChat(chatId: Long)

    suspend fun deleteExpired(timestamp: Long)
}

private fun MessageWindowEntity.withInvalidCoverage() = copy(
    oldestMessageId = null,
    newestMessageId = null,
    olderBoundaryReached = false,
    newerBoundaryReached = false
)
