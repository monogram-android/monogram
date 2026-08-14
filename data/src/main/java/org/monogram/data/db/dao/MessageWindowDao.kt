package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.data.db.model.MessageWindowEntity

@Dao
interface MessageWindowDao {
    @Query("SELECT * FROM message_windows WHERE chatId = :chatId AND scopeType = :scopeType AND scopeId = :scopeId")
    suspend fun get(chatId: Long, scopeType: String, scopeId: Long): MessageWindowEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(window: MessageWindowEntity)

    @Query(
        """
        UPDATE message_windows
        SET oldestMessageId = NULL,
            newestMessageId = NULL,
            olderBoundaryReached = 0,
            newerBoundaryReached = 0
        WHERE chatId = :chatId AND scopeType = :scopeType AND scopeId = :scopeId
        """
    )
    suspend fun invalidateCoverage(chatId: Long, scopeType: String, scopeId: Long)

    @Query(
        """
        UPDATE message_windows
        SET oldestMessageId = NULL,
            newestMessageId = NULL,
            olderBoundaryReached = 0,
            newerBoundaryReached = 0
        WHERE chatId = :chatId
        """
    )
    suspend fun invalidateCoverageForChat(chatId: Long)

    @Query(
        """
        UPDATE message_windows
        SET oldestMessageId = NULL,
            newestMessageId = NULL,
            olderBoundaryReached = 0,
            newerBoundaryReached = 0
        """
    )
    suspend fun invalidateAllCoverage()

    @Query("DELETE FROM message_windows WHERE chatId = :chatId AND scopeType = :scopeType AND scopeId = :scopeId")
    suspend fun delete(chatId: Long, scopeType: String, scopeId: Long)

    @Query("DELETE FROM message_windows WHERE chatId = :chatId")
    suspend fun deleteForChat(chatId: Long)

    @Query("DELETE FROM message_windows")
    suspend fun clearAll()
}
