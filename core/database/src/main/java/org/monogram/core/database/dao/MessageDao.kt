package org.monogram.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.core.database.entity.MessageEntity

@Dao
interface MessageDao {
    @Query("UPDATE messages SET reactionsJson = :json WHERE chatId = :chatId AND id = :messageId")
    suspend fun updateReactions(chatId: Long, messageId: Int, json: String)
    @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chatId AND id > :maxId AND outgoing = 0")
    suspend fun countIncomingAfter(chatId: Long, maxId: Int): Int

    @Query(
        """
        SELECT * FROM messages
        WHERE chatId = :chatId AND pending = 0
        ORDER BY id DESC
        LIMIT :limit
        """,
    )
    suspend fun latestForChat(chatId: Long, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND pending = 1")
    suspend fun pendingForChat(chatId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND id IN (:ids)")
    suspend fun byIds(chatId: Long, ids: List<Int>): List<MessageEntity>

    @Query("SELECT DISTINCT chatId FROM messages WHERE failed = 1 OR ((pending = 1 OR id < 0) AND (randomId IS NULL OR randomId = 0))")
    suspend fun startupTransientChatIds(): List<Long>

    @Query("DELETE FROM messages WHERE failed = 1 OR ((pending = 1 OR id < 0) AND (randomId IS NULL OR randomId = 0))")
    suspend fun deleteStartupTransient()

    @Query("UPDATE messages SET failed = 1, pending = 0 WHERE chatId = :chatId AND id = :messageId")
    suspend fun markFailed(chatId: Long, messageId: Int)

    @Query(
        """
        SELECT * FROM messages
        WHERE chatId = :chatId AND pending = 0 AND id < :beforeId
        ORDER BY id DESC
        LIMIT :limit
        """,
    )
    suspend fun olderThan(chatId: Long, beforeId: Int, limit: Int): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE chatId = :chatId AND pending = 0 AND id > :afterId
        ORDER BY id ASC
        LIMIT :limit
        """,
    )
    suspend fun newerThan(chatId: Long, afterId: Int, limit: Int): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE chatId = :chatId AND pending = 0 AND date <= :beforeDate
        ORDER BY id DESC
        LIMIT :limit
        """,
    )
    suspend fun atOrBeforeDate(chatId: Long, beforeDate: Long, limit: Int): List<MessageEntity>

    @Query(
        """
        SELECT id FROM messages
        WHERE chatId = :chatId AND pending = 0 AND id > :messageId
        ORDER BY id DESC
        LIMIT 200
        """,
    )
    suspend fun idsAfter(chatId: Long, messageId: Int): List<Int>

    @Query(
        """
        SELECT id FROM messages
        WHERE chatId = :chatId AND pending = 0 AND id >= :messageId
        ORDER BY id DESC
        LIMIT 200
        """,
    )
    suspend fun idsAtOrAfter(chatId: Long, messageId: Int): List<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE chatId = :chatId AND id = :id")
    suspend fun delete(chatId: Long, id: Int)

    @Query("DELETE FROM messages WHERE chatId = :chatId AND id IN (:ids)")
    suspend fun deleteInChat(chatId: Long, ids: List<Int>)

    @Query("DELETE FROM messages WHERE chatId = :chatId AND id > :lastMessageId")
    suspend fun deleteAfter(chatId: Long, lastMessageId: Int)

    @Query("DELETE FROM messages WHERE id IN (:ids) AND chatId > -1000000000000")
    suspend fun deleteUserSpaceIds(ids: List<Int>)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun clearChat(chatId: Long)

    @Query("SELECT DISTINCT chatId FROM messages LIMIT :limit OFFSET :offset")
    suspend fun chatIdsPage(limit: Int, offset: Int): List<Long>

    @Query(
        """
        SELECT id FROM messages
        WHERE chatId = :chatId AND pending = 0 AND failed = 0
        ORDER BY id DESC
        LIMIT :limit
        """,
    )
    suspend fun newestConfirmedIds(chatId: Long, limit: Int): List<Int>

    @Query(
        """
        SELECT id FROM messages
        WHERE chatId = :chatId AND pending = 0 AND failed = 0 AND date < :cutoffSeconds
        ORDER BY id ASC
        LIMIT :limit
        """,
    )
    suspend fun oldConfirmedIds(chatId: Long, cutoffSeconds: Long, limit: Int): List<Int>

    @Query(
        """
        SELECT id FROM messages
        WHERE chatId = :chatId AND (pending = 1 OR failed = 1 OR date >= :cutoffSeconds)
        ORDER BY id DESC
        LIMIT :limit
        """,
    )
    suspend fun stickyIds(chatId: Long, cutoffSeconds: Long, limit: Int): List<Int>

    @Query("DELETE FROM messages WHERE chatId = :chatId AND pending = 0 AND id NOT IN (:keep)")
    suspend fun deleteExcept(chatId: Long, keep: List<Int>)

    @Query("DELETE FROM messages")
    suspend fun clearAll()
}
