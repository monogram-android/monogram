package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.monogram.data.db.model.MessageEntity

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY date DESC")
    fun getMessagesForChat(chatId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND id < :fromMessageId ORDER BY date DESC LIMIT :limit")
    suspend fun getMessagesOlder(chatId: Long, fromMessageId: Long, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND id > :fromMessageId ORDER BY date ASC LIMIT :limit")
    suspend fun getMessagesNewer(chatId: Long, fromMessageId: Long, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY date DESC LIMIT :limit")
    suspend fun getLatestMessages(chatId: Long, limit: Int): List<MessageEntity>

    @Query("UPDATE messages SET isRead = 1 WHERE chatId = :chatId AND id <= :upToMessageId AND isRead = 0")
    suspend fun markAsRead(chatId: Long, upToMessageId: Long)

    @Query(
        "UPDATE messages SET content = :content, contentType = :contentType, contentMeta = :contentMeta, mediaFileId = :mediaFileId, mediaPath = :mediaPath, editDate = :editDate WHERE id = :messageId"
    )
    suspend fun updateContent(
        messageId: Long,
        content: String,
        contentType: String,
        contentMeta: String?,
        mediaFileId: Int,
        mediaPath: String?,
        editDate: Int
    )

    @Query("UPDATE messages SET mediaPath = :path WHERE mediaFileId = :fileId AND mediaFileId != 0")
    suspend fun updateMediaPath(fileId: Int, path: String)

    @Query("UPDATE messages SET viewCount = :viewCount, forwardCount = :forwardCount, replyCount = :replyCount WHERE id = :messageId")
    suspend fun updateInteractionInfo(messageId: Long, viewCount: Int, forwardCount: Int, replyCount: Int)

    @Query(
        """
        DELETE FROM messages
        WHERE chatId = :chatId
          AND id NOT IN (
            SELECT id FROM messages WHERE chatId = :chatId ORDER BY date DESC LIMIT :keepCount
          )
          AND createdAt < :olderThan
        """
    )
    suspend fun cleanupChat(chatId: Long, keepCount: Int, olderThan: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: Long)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun clearMessagesForChat(chatId: Long)

    @Query("DELETE FROM messages")
    suspend fun clearAll()

    @Query("DELETE FROM messages WHERE createdAt < :timestamp")
    suspend fun deleteExpired(timestamp: Long)
}