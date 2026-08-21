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
import org.monogram.data.db.model.MessageWindowEntity
import org.monogram.data.db.dao.MessageWindowDao

class RoomChatLocalDataSource(
    private val database: MonogramDatabase,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val chatFullInfoDao: ChatFullInfoDao,
    private val topicDao: TopicDao,
    private val messageWindowDao: MessageWindowDao
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
            messageWindowDao.clearAll()
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

    override suspend fun getMessageWindow(
        chatId: Long,
        scopeType: String,
        scopeId: Long
    ): MessageWindowEntity? =
        messageWindowDao.get(chatId, scopeType, scopeId)

    override suspend fun upsertMessageWindow(window: MessageWindowEntity) =
        messageWindowDao.upsert(window)

    override suspend fun deleteMessageWindow(chatId: Long, scopeType: String, scopeId: Long) =
        messageWindowDao.delete(chatId, scopeType, scopeId)

    override suspend fun deleteMessageWindowsForChat(chatId: Long) =
        messageWindowDao.deleteForChat(chatId)

    override suspend fun updateProtectedMessageId(
        chatId: Long,
        scopeType: String,
        scopeId: Long,
        messageId: Long?
    ) {
        database.withTransaction {
            super.updateProtectedMessageId(chatId, scopeType, scopeId, messageId)
        }
    }

    override suspend fun invalidateMessageWindowCoverage(
        chatId: Long,
        scopeType: String,
        scopeId: Long
    ) = messageWindowDao.invalidateCoverage(chatId, scopeType, scopeId)

    override suspend fun invalidateMessageWindowCoverageForChat(chatId: Long) =
        messageWindowDao.invalidateCoverageForChat(chatId)

    override suspend fun insertMessage(message: MessageEntity) {
        database.withTransaction {
            messageDao.insertMessage(message)
            cleanupMessageCache(message.chatId, message.threadId)
        }
    }

    override suspend fun insertMessages(messages: List<MessageEntity>) {
        if (messages.isEmpty()) return
        database.withTransaction {
            messageDao.insertMessages(messages)
            for ((chatId, threadId) in messages.asSequence().map { it.chatId to it.threadId }
                .distinct()) {
                cleanupMessageCache(chatId, threadId)
            }
        }
    }

    override suspend fun persistHistoryMessages(writes: List<MessageCacheMutation.HistoryWrite>) {
        if (writes.isEmpty()) return
        database.withTransaction {
            writes.groupBy { it.message.chatId }.forEach { (chatId, chatWrites) ->
                val currentById = messageDao
                    .getMessagesByIds(chatId, chatWrites.map { it.message.id })
                    .associateBy { it.id }
                val applicable = chatWrites.mapNotNull { write ->
                    write.message.takeIf { currentById[write.message.id] == write.expectedExisting }
                }
                if (applicable.isNotEmpty()) messageDao.insertMessages(applicable)
                chatWrites.asSequence().map { it.message.threadId }.distinct().forEach { threadId ->
                    cleanupMessageCache(chatId, threadId)
                }
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
        // Telegram file ids and local paths are session-local
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

    override suspend fun deleteMessage(chatId: Long, messageId: Long) {
        database.withTransaction {
            messageDao.deleteMessage(chatId, messageId)
            messageWindowDao.invalidateCoverageForChat(chatId)
        }
    }

    override suspend fun deleteMessages(chatId: Long, messageIds: List<Long>) {
        if (messageIds.isNotEmpty()) {
            database.withTransaction {
                messageDao.deleteMessages(chatId, messageIds)
                messageWindowDao.invalidateCoverageForChat(chatId)
            }
        }
    }

    override suspend fun applyMessageCacheMutations(mutations: List<MessageCacheMutation>) {
        if (mutations.isEmpty()) return

        database.withTransaction {
            mutations.forEach { mutation ->
                when (mutation) {
                    is MessageCacheMutation.Persist -> messageDao.insertMessage(mutation.message)
                    is MessageCacheMutation.PersistHistoryBatch -> {
                        mutation.writes.groupBy { it.message.chatId }.forEach { (chatId, writes) ->
                            val currentById = messageDao
                                .getMessagesByIds(chatId, writes.map { it.message.id })
                                .associateBy { it.id }
                            val applicable = writes.mapNotNull { write ->
                                write.message.takeIf { currentById[write.message.id] == write.expectedExisting }
                            }
                            if (applicable.isNotEmpty()) messageDao.insertMessages(applicable)
                        }
                    }

                    is MessageCacheMutation.UpdateWindow -> Unit
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
                            messageWindowDao.invalidateCoverageForChat(mutation.chatId)
                        }
                    }

                    is MessageCacheMutation.UpdateMediaPath -> Unit
                }
            }
            mutations.forEach { mutation ->
                when (mutation) {
                    is MessageCacheMutation.PersistHistoryBatch ->
                        messageWindowDao.upsert(mutation.window)

                    is MessageCacheMutation.UpdateWindow -> messageWindowDao.upsert(mutation.window)
                    else -> Unit
                }
            }
            for (cacheScope in mutations.asSequence().mapNotNull(::cacheGrowthScope).distinct()) {
                cleanupMessageCache(cacheScope)
            }
        }
    }

    private suspend fun cleanupMessageCache(chatId: Long, threadId: Long?) = cleanupMessageCache(
        CacheCleanupScope(
            chatId = chatId,
            threadId = threadId,
            scopeType = if (threadId == null) "main" else null,
            scopeId = if (threadId == null) 0L else null
        )
    )

    private suspend fun cleanupMessageCache(scope: CacheCleanupScope) {
        val olderThan = System.currentTimeMillis() - MESSAGE_CACHE_TTL_MS
        val protectedMessageId = messageWindowDao
            .let { dao ->
                val scopeType = scope.scopeType ?: return@let null
                dao.get(scope.chatId, scopeType, scope.scopeId ?: 0L)
            }
            ?.protectedMessageId
        val removedFromScope = if (scope.threadId == null) {
            messageDao.cleanupMainScope(
                scope.chatId,
                MAIN_SCOPE_ROWS,
                olderThan,
                protectedMessageId
            )
        } else {
            messageDao.cleanupThreadScope(
                scope.chatId,
                scope.threadId,
                THREAD_SCOPE_ROWS,
                olderThan,
                protectedMessageId
            )
        }
        if (removedFromScope > 0) {
            val scopeType = scope.scopeType
            if (scopeType == null) {
                messageWindowDao.invalidateCoverageForChat(scope.chatId)
            } else {
                messageWindowDao.invalidateCoverage(scope.chatId, scopeType, scope.scopeId ?: 0L)
            }
        }
        if (messageDao.enforceChatCap(scope.chatId, MESSAGE_CACHE_ROWS_PER_CHAT) > 0) {
            messageWindowDao.invalidateCoverageForChat(scope.chatId)
        }
    }

    private fun cacheGrowthScope(mutation: MessageCacheMutation): CacheCleanupScope? =
        when (mutation) {
            is MessageCacheMutation.Persist -> mutation.toCleanupScope()
            is MessageCacheMutation.PersistHistoryBatch -> mutation.window.let { window ->
                CacheCleanupScope(
                    chatId = window.chatId,
                    threadId = mutation.writes.firstOrNull()?.message?.threadId
                        ?: window.scopeId.takeIf { window.scopeType != "main" },
                    scopeType = window.scopeType,
                    scopeId = window.scopeId
                )
            }

            is MessageCacheMutation.UpdateWindow,
            is MessageCacheMutation.ReplaceId,
            is MessageCacheMutation.UpdateContent,
            is MessageCacheMutation.UpdateInteraction,
            is MessageCacheMutation.MarkRead,
            is MessageCacheMutation.DeleteMessages,
            is MessageCacheMutation.UpdateMediaPath -> null
    }

    private fun MessageEntity.toCleanupScope() = CacheCleanupScope(
        chatId = chatId,
        threadId = threadId,
        scopeType = if (threadId == null) "main" else null,
        scopeId = if (threadId == null) 0L else null
    )

    private fun MessageCacheMutation.Persist.toCleanupScope() = CacheCleanupScope(
        chatId = message.chatId,
        threadId = message.threadId,
        scopeType = key.scopeType,
        scopeId = key.scopeId
    )

    private data class CacheCleanupScope(
        val chatId: Long,
        val threadId: Long?,
        val scopeType: String?,
        val scopeId: Long?
    )

    override suspend fun clearMessagesForChat(chatId: Long) {
        database.withTransaction {
            messageDao.clearMessagesForChat(chatId)
            messageWindowDao.deleteForChat(chatId)
        }
    }

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
            messageWindowDao.invalidateAllCoverage()
        }
    }

    private companion object {
        const val MAIN_SCOPE_ROWS = 500
        const val THREAD_SCOPE_ROWS = 250
        const val MESSAGE_CACHE_ROWS_PER_CHAT = 1_500
        const val MESSAGE_CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1_000
    }
}
