package org.monogram.data.datasource.cache

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.monogram.data.db.MonogramDatabase
import org.monogram.data.db.dao.ChatDao
import org.monogram.data.db.dao.ChatFullInfoDao
import org.monogram.data.db.dao.MessageDao
import org.monogram.data.db.dao.TopicDao
import org.monogram.data.db.model.ChatEntity
import org.monogram.data.db.model.ChatFullInfoEntity
import org.monogram.data.db.model.MessageEntity
import org.monogram.data.db.model.TopicEntity

class RoomChatLocalDataSource(
    private val database: MonogramDatabase,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val chatFullInfoDao: ChatFullInfoDao,
    private val topicDao: TopicDao
) : ChatLocalDataSource {
    override fun getAllChats(): Flow<List<ChatEntity>> = chatDao.getAllChats()

    override suspend fun getTopChats(limit: Int): List<ChatEntity> = chatDao.getTopChats(limit)

    override suspend fun getStartupChats(limit: Int): List<ChatEntity> =
        chatDao.getStartupChats(limit)

    override suspend fun getChat(chatId: Long): ChatEntity? = chatDao.getChat(chatId)

    override suspend fun insertChat(chat: ChatEntity) = chatDao.insertChat(chat)

    override suspend fun insertChats(chats: List<ChatEntity>) = chatDao.insertChats(chats)

    override suspend fun deleteChat(chatId: Long) = chatDao.deleteChat(chatId)

    override suspend fun clearAllChats() = chatDao.clearAll()

    override suspend fun clearAll() {
        database.withTransaction {
            chatDao.clearAll()
            messageDao.clearAll()
            chatFullInfoDao.clearAll()
            topicDao.clearAll()
        }
    }

    override fun getMessagesForChat(chatId: Long): Flow<List<MessageEntity>> = messageDao.getMessagesForChat(chatId)

    override suspend fun getMessagesOlder(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
        threadId: Long?
    ) =
        if (threadId == null) {
            messageDao.getMainChatMessagesOlder(chatId, fromMessageId, limit)
        } else {
            messageDao.getThreadMessagesOlder(chatId, threadId, fromMessageId, limit)
        }

    override suspend fun getMessagesNewer(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
        threadId: Long?
    ) =
        if (threadId == null) {
            messageDao.getMainChatMessagesNewer(chatId, fromMessageId, limit)
        } else {
            messageDao.getThreadMessagesNewer(chatId, threadId, fromMessageId, limit)
        }

    override suspend fun getMessagesAround(
        chatId: Long,
        messageId: Long,
        limit: Int,
        threadId: Long?
    ) =
        if (threadId == null) {
            messageDao.getMainChatMessagesAround(
                chatId = chatId,
                messageId = messageId,
                olderLimit = (limit + 1) / 2,
                newerLimit = limit / 2
            )
        } else {
            messageDao.getThreadMessagesAround(
                chatId = chatId,
                threadId = threadId,
                messageId = messageId,
                olderLimit = (limit + 1) / 2,
                newerLimit = limit / 2
            )
        }

    override suspend fun getLatestMessages(chatId: Long, limit: Int, threadId: Long?) =
        if (threadId == null) {
            messageDao.getMainChatLatestMessages(chatId, limit)
        } else {
            messageDao.getThreadLatestMessages(chatId, threadId, limit)
        }

    override suspend fun getMessagesByIds(chatId: Long, messageIds: List<Long>) =
        if (messageIds.isEmpty()) emptyList() else messageDao.getMessagesByIds(chatId, messageIds)

    override suspend fun insertMessage(message: MessageEntity) {
        database.withTransaction {
            messageDao.insertMessage(message)
            cleanupMessageCache(message.chatId)
        }
    }

    override suspend fun insertMessages(messages: List<MessageEntity>) {
        if (messages.isEmpty()) return
        database.withTransaction {
            messageDao.insertMessages(messages)
            for (chatId in messages.asSequence().map(MessageEntity::chatId).distinct()) {
                cleanupMessageCache(chatId)
            }
        }
    }

    override suspend fun replaceMessage(message: MessageEntity) = messageDao.insertMessage(message)

    override suspend fun replaceMessageId(
        chatId: Long,
        oldMessageId: Long,
        message: MessageEntity
    ) {
        database.withTransaction {
            messageDao.deleteMessage(chatId, oldMessageId)
            messageDao.insertMessage(message)
        }
    }

    override suspend fun markAsRead(chatId: Long, upToMessageId: Long) = messageDao.markAsRead(chatId, upToMessageId)

    override suspend fun updateMessageContent(
        chatId: Long,
        messageId: Long,
        content: String,
        contentType: String,
        contentMeta: String?,
        mediaFileId: Int,
        mediaPath: String?,
        editDate: Int
    ) = messageDao.updateContent(
        chatId,
        messageId,
        content,
        contentType,
        contentMeta,
        0,
        null,
        editDate
    )

    override suspend fun updateMediaPath(chatId: Long, messageId: Long, fileId: Int, path: String) {
        // TDLib file ids and local paths are session-local
    }

    override suspend fun clearCachedMediaPaths() = messageDao.clearCachedMediaPaths()

    override suspend fun clearCachedChatAvatarPaths() = chatDao.clearAvatarPaths()

    override suspend fun updateInteractionInfo(
        chatId: Long,
        messageId: Long,
        viewCount: Int,
        forwardCount: Int,
        replyCount: Int
    ) =
        messageDao.updateInteractionInfo(chatId, messageId, viewCount, forwardCount, replyCount)

    override suspend fun deleteMessage(chatId: Long, messageId: Long) =
        messageDao.deleteMessage(chatId, messageId)

    override suspend fun deleteMessages(chatId: Long, messageIds: List<Long>) {
        if (messageIds.isNotEmpty()) {
            messageDao.deleteMessages(chatId, messageIds)
        }
    }

    override suspend fun applyMessageCacheMutations(mutations: List<MessageCacheMutation>) {
        if (mutations.isEmpty()) return

        database.withTransaction {
            mutations.forEach { mutation ->
                when (mutation) {
                    is MessageCacheMutation.Persist -> messageDao.insertMessage(mutation.message)
                    is MessageCacheMutation.ReplaceId -> {
                        messageDao.deleteMessage(mutation.chatId, mutation.oldMessageId)
                        messageDao.insertMessage(mutation.message)
                    }

                    is MessageCacheMutation.UpdateContent -> messageDao.updateContent(
                        mutation.chatId,
                        mutation.messageId,
                        mutation.content,
                        mutation.contentType,
                        mutation.contentMeta,
                        0,
                        null,
                        mutation.editDate
                    )

                    is MessageCacheMutation.UpdateInteraction -> messageDao.updateInteractionInfo(
                        mutation.chatId,
                        mutation.messageId,
                        mutation.viewCount,
                        mutation.forwardCount,
                        mutation.replyCount
                    )

                    is MessageCacheMutation.MarkRead -> messageDao.markAsRead(
                        mutation.chatId,
                        mutation.upToMessageId
                    )

                    is MessageCacheMutation.DeleteMessages -> {
                        if (mutation.messageIds.isNotEmpty()) {
                            messageDao.deleteMessages(mutation.chatId, mutation.messageIds)
                        }
                    }

                    is MessageCacheMutation.UpdateMediaPath -> Unit
                }
            }
            for (chatId in mutations.asSequence().map(::mutationChatId).distinct()) {
                cleanupMessageCache(chatId)
            }
        }
    }

    private suspend fun cleanupMessageCache(chatId: Long) {
        messageDao.cleanupChat(
            chatId = chatId,
            keepCount = MESSAGE_CACHE_ROWS_PER_CHAT,
            olderThan = System.currentTimeMillis() - MESSAGE_CACHE_TTL_MS
        )
    }

    private fun mutationChatId(mutation: MessageCacheMutation): Long = when (mutation) {
        is MessageCacheMutation.Persist -> mutation.message.chatId
        is MessageCacheMutation.ReplaceId -> mutation.chatId
        is MessageCacheMutation.UpdateContent -> mutation.chatId
        is MessageCacheMutation.UpdateInteraction -> mutation.chatId
        is MessageCacheMutation.MarkRead -> mutation.chatId
        is MessageCacheMutation.DeleteMessages -> mutation.chatId
        is MessageCacheMutation.UpdateMediaPath -> mutation.chatId
    }

    override suspend fun clearMessagesForChat(chatId: Long) = messageDao.clearMessagesForChat(chatId)

    override suspend fun getChatFullInfo(chatId: Long): ChatFullInfoEntity? = chatFullInfoDao.getChatFullInfo(chatId)

    override suspend fun insertChatFullInfo(info: ChatFullInfoEntity) = chatFullInfoDao.insertChatFullInfo(info)

    override suspend fun deleteChatFullInfo(chatId: Long) = chatFullInfoDao.deleteChatFullInfo(chatId)

    override fun getTopicsForChat(chatId: Long): Flow<List<TopicEntity>> = topicDao.getTopicsForChat(chatId)

    override suspend fun insertTopic(topic: TopicEntity) = topicDao.insertTopic(topic)

    override suspend fun insertTopics(topics: List<TopicEntity>) = topicDao.insertTopics(topics)

    override suspend fun deleteTopic(chatId: Long, topicId: Int) = topicDao.deleteTopic(chatId, topicId)

    override suspend fun clearTopicsForChat(chatId: Long) = topicDao.clearTopicsForChat(chatId)

    override suspend fun deleteExpired(timestamp: Long) {
        database.withTransaction {
            messageDao.deleteExpired(timestamp)
        }
    }

    private companion object {
        const val MESSAGE_CACHE_ROWS_PER_CHAT = 1_000
        const val MESSAGE_CACHE_TTL_MS = 90L * 24 * 60 * 60 * 1_000
    }
}
