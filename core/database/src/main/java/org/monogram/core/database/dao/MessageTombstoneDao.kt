package org.monogram.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.core.database.entity.MessageTombstoneEntity

@Dao
interface MessageTombstoneDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(rows: List<MessageTombstoneEntity>)

    @Query("SELECT id FROM message_tombstones WHERE id IN (:ids) AND (chatId = :chatId OR (chatId = 0 AND :chatId > -1000000000000))")
    suspend fun deletedIds(chatId: Long, ids: List<Int>): List<Int>

    @Query("DELETE FROM message_tombstones WHERE chatId = :chatId AND id IN (:ids)")
    suspend fun deleteIds(chatId: Long, ids: List<Int>)

    @Query("DELETE FROM message_tombstones WHERE chatId = :chatId AND id < :oldestKept")
    suspend fun deleteBelow(chatId: Long, oldestKept: Int)

    @Query(
        """
        SELECT chatId FROM message_tombstones
        WHERE chatId != 0 AND chatId NOT IN (SELECT DISTINCT chatId FROM messages)
        GROUP BY chatId
        LIMIT :limit
        """,
    )
    suspend fun chatsWithoutMessages(limit: Int): List<Long>

    @Query("DELETE FROM message_tombstones WHERE chatId IN (:chatIds)")
    suspend fun deleteChats(chatIds: List<Long>)

    @Query(
        """
        DELETE FROM message_tombstones
        WHERE chatId = 0 AND id NOT IN (
            SELECT id FROM message_tombstones WHERE chatId = 0 ORDER BY id DESC LIMIT :keep
        )
        """,
    )
    suspend fun trimUserSpace(keep: Int)

    @Query("DELETE FROM message_tombstones")
    suspend fun clear()
}
