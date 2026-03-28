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

    override suspend fun getMessagesOlder(chatId: Long, fromMessageId: Long, limit: Int) = messageDao.getMessagesOlder(chatId, fromMessageId, limit)

    override suspend fun getMessagesNewer(chatId: Long, fromMessageId: Long, limit: Int) = messageDao.getMessagesNewer(chatId, fromMessageId, limit)

    override suspend fun getLatestMessages(chatId: Long, limit: Int) = messageDao.getLatestMessages(chatId, limit)

    override suspend fun insertMessage(message: MessageEntity) = messageDao.insertMessage(message)

    override suspend fun insertMessages(messages: List<MessageEntity>) = messageDao.insertMessages(messages)

    override suspend fun markAsRead(chatId: Long, upToMessageId: Long) = messageDao.markAsRead(chatId, upToMessageId)

    override suspend fun updateMessageContent(
        messageId: Long,
        content: String,
        contentType: String,
        contentMeta: String?,
        mediaFileId: Int,
        mediaPath: String?,
        editDate: Int
    ) = messageDao.updateContent(messageId, content, contentType, contentMeta, mediaFileId, mediaPath, editDate)

    override suspend fun updateMediaPath(fileId: Int, path: String) = messageDao.updateMediaPath(fileId, path)

    override suspend fun updateInteractionInfo(messageId: Long, viewCount: Int, forwardCount: Int, replyCount: Int) =
        messageDao.updateInteractionInfo(messageId, viewCount, forwardCount, replyCount)

    override suspend fun deleteMessage(messageId: Long) = messageDao.deleteMessage(messageId)

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
            chatDao.deleteExpired(timestamp)
            messageDao.deleteExpired(timestamp)
            chatFullInfoDao.deleteExpired(timestamp)
            topicDao.deleteExpired(timestamp)
        }
    }
}