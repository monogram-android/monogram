package org.monogram.data.datasource.cache

import kotlinx.coroutines.flow.Flow
import org.monogram.data.db.model.ChatEntity
import org.monogram.data.db.model.ChatFullInfoEntity
import org.monogram.data.db.model.MessageEntity
import org.monogram.data.db.model.TopicEntity

interface ChatLocalDataSource {
    fun getAllChats(): Flow<List<ChatEntity>>
    suspend fun getChat(chatId: Long): ChatEntity?
    suspend fun insertChat(chat: ChatEntity)
    suspend fun insertChats(chats: List<ChatEntity>)
    suspend fun deleteChat(chatId: Long)
    suspend fun clearAllChats()

    fun getMessagesForChat(chatId: Long): Flow<List<MessageEntity>>
    suspend fun getMessagesOlder(chatId: Long, fromMessageId: Long, limit: Int): List<MessageEntity>
    suspend fun getMessagesNewer(chatId: Long, fromMessageId: Long, limit: Int): List<MessageEntity>
    suspend fun insertMessage(message: MessageEntity)
    suspend fun insertMessages(messages: List<MessageEntity>)
    suspend fun deleteMessage(messageId: Long)
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