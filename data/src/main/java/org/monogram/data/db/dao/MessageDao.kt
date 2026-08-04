package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.monogram.data.db.model.MessageEntity

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY date DESC, id DESC")
    fun getMessagesForChat(chatId: Long): Flow<List<MessageEntity>>

    @Query(
        "SELECT * FROM messages WHERE chatId = :chatId AND threadId IS NULL AND id < :fromMessageId ORDER BY date DESC, id DESC LIMIT :limit"
    )
    suspend fun getMainChatMessagesOlder(
        chatId: Long,
        fromMessageId: Long,
        limit: Int
    ): List<MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE chatId = :chatId AND threadId = :threadId AND id < :fromMessageId ORDER BY date DESC, id DESC LIMIT :limit"
    )
    suspend fun getThreadMessagesOlder(
        chatId: Long,
        threadId: Long,
        fromMessageId: Long,
        limit: Int
    ): List<MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE chatId = :chatId AND threadId IS NULL AND id > :fromMessageId ORDER BY date ASC, id ASC LIMIT :limit"
    )
    suspend fun getMainChatMessagesNewer(
        chatId: Long,
        fromMessageId: Long,
        limit: Int
    ): List<MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE chatId = :chatId AND threadId = :threadId AND id > :fromMessageId ORDER BY date ASC, id ASC LIMIT :limit"
    )
    suspend fun getThreadMessagesNewer(
        chatId: Long,
        threadId: Long,
        fromMessageId: Long,
        limit: Int
    ): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE chatId = :chatId
          AND threadId IS NULL
          AND id IN (
            SELECT id FROM messages
            WHERE chatId = :chatId AND threadId IS NULL AND id <= :messageId
            ORDER BY date DESC, id DESC LIMIT :olderLimit
          )
        UNION
        SELECT * FROM messages
        WHERE chatId = :chatId
          AND threadId IS NULL
          AND id IN (
            SELECT id FROM messages
            WHERE chatId = :chatId AND threadId IS NULL AND id > :messageId
            ORDER BY date ASC, id ASC LIMIT :newerLimit
          )
        ORDER BY date DESC, id DESC
        """
    )
    suspend fun getMainChatMessagesAround(
        chatId: Long,
        messageId: Long,
        olderLimit: Int,
        newerLimit: Int
    ): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE chatId = :chatId
          AND threadId = :threadId
          AND id IN (
            SELECT id FROM messages
            WHERE chatId = :chatId AND threadId = :threadId AND id <= :messageId
            ORDER BY date DESC, id DESC LIMIT :olderLimit
          )
        UNION
        SELECT * FROM messages
        WHERE chatId = :chatId
          AND threadId = :threadId
          AND id IN (
            SELECT id FROM messages
            WHERE chatId = :chatId AND threadId = :threadId AND id > :messageId
            ORDER BY date ASC, id ASC LIMIT :newerLimit
          )
        ORDER BY date DESC, id DESC
        """
    )
    suspend fun getThreadMessagesAround(
        chatId: Long,
        threadId: Long,
        messageId: Long,
        olderLimit: Int,
        newerLimit: Int
    ): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND threadId IS NULL ORDER BY date DESC, id DESC LIMIT :limit")
    suspend fun getMainChatLatestMessages(chatId: Long, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND threadId = :threadId ORDER BY date DESC, id DESC LIMIT :limit")
    suspend fun getThreadLatestMessages(
        chatId: Long,
        threadId: Long,
        limit: Int
    ): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND id IN (:messageIds)")
    suspend fun getMessagesByIds(chatId: Long, messageIds: List<Long>): List<MessageEntity>

    @Query("UPDATE messages SET isRead = 1 WHERE chatId = :chatId AND id <= :upToMessageId AND isRead = 0")
    suspend fun markAsRead(chatId: Long, upToMessageId: Long)

    @Query(
        "UPDATE messages SET content = :content, contentType = :contentType, contentMeta = :contentMeta, mediaFileId = :mediaFileId, mediaPath = :mediaPath, editDate = :editDate WHERE chatId = :chatId AND id = :messageId"
    )
    suspend fun updateContent(
        chatId: Long,
        messageId: Long,
        content: String,
        contentType: String,
        contentMeta: String?,
        mediaFileId: Int,
        mediaPath: String?,
        editDate: Int
    )

    @Query(
        "UPDATE messages SET mediaPath = :path WHERE chatId = :chatId AND id = :messageId AND mediaFileId = :fileId AND mediaFileId != 0"
    )
    suspend fun updateMediaPathForMessage(chatId: Long, messageId: Long, fileId: Int, path: String)

    @Query(
        "UPDATE messages SET mediaFileId = 0, mediaPath = NULL, mediaThumbnailPath = NULL WHERE (mediaFileId != 0 OR mediaPath IS NOT NULL OR mediaThumbnailPath IS NOT NULL) AND contentType IN ('photo', 'video', 'video_note', 'document', 'gif', 'voice', 'sticker', 'audio')"
    )
    suspend fun clearCachedMediaPaths()

    @Query("UPDATE messages SET viewCount = :viewCount, forwardCount = :forwardCount, replyCount = :replyCount WHERE chatId = :chatId AND id = :messageId")
    suspend fun updateInteractionInfo(
        chatId: Long,
        messageId: Long,
        viewCount: Int,
        forwardCount: Int,
        replyCount: Int
    )

    @Query(
        """
        DELETE FROM messages
        WHERE chatId = :chatId
          AND (
            createdAt < :olderThan
            OR id NOT IN (
              SELECT id FROM messages WHERE chatId = :chatId ORDER BY date DESC LIMIT :keepCount
            )
          )
        """
    )
    suspend fun cleanupChat(chatId: Long, keepCount: Int, olderThan: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE chatId = :chatId AND id = :messageId")
    suspend fun deleteMessage(chatId: Long, messageId: Long)

    @Query("DELETE FROM messages WHERE chatId = :chatId AND id IN (:messageIds)")
    suspend fun deleteMessages(chatId: Long, messageIds: List<Long>)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun clearMessagesForChat(chatId: Long)

    @Query("DELETE FROM messages")
    suspend fun clearAll()

    @Query("DELETE FROM messages WHERE createdAt < :timestamp")
    suspend fun deleteExpired(timestamp: Long)
}