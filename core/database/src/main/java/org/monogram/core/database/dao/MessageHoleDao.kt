package org.monogram.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.core.database.entity.MessageHoleEntity

@Dao
interface MessageHoleDao {
    @Query("SELECT EXISTS(SELECT 1 FROM message_holes WHERE chatId = :chatId AND source = :source AND startId <= :endId AND endId >= :startId)")
    suspend fun overlaps(chatId: Long, startId: Int, endId: Int, source: String): Boolean

    @Query("SELECT * FROM message_holes WHERE chatId = :chatId AND source = :source ORDER BY startId")
    suspend fun forChat(chatId: Long, source: String): List<MessageHoleEntity>

    @Query("DELETE FROM message_holes WHERE chatId = :chatId AND source = :source")
    suspend fun clearChat(chatId: Long, source: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(hole: MessageHoleEntity)

    @Query("DELETE FROM message_holes")
    suspend fun clearAll()
}
