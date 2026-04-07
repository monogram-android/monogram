package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import org.monogram.data.db.model.NotificationExceptionEntity

@Dao
interface NotificationExceptionDao {
    @Query("SELECT * FROM notification_exceptions WHERE scope = :scope ORDER BY updatedAt DESC")
    suspend fun getByScope(scope: String): List<NotificationExceptionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: NotificationExceptionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<NotificationExceptionEntity>)

    @Query("DELETE FROM notification_exceptions WHERE scope = :scope")
    suspend fun deleteByScope(scope: String)

    @Query("DELETE FROM notification_exceptions WHERE chatId = :chatId")
    suspend fun deleteByChatId(chatId: Long)

    @Query("UPDATE notification_exceptions SET isMuted = :isMuted, updatedAt = :updatedAt WHERE chatId = :chatId")
    suspend fun updateMute(chatId: Long, isMuted: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Transaction
    suspend fun replaceForScope(scope: String, entities: List<NotificationExceptionEntity>) {
        deleteByScope(scope)
        if (entities.isNotEmpty()) {
            insertAll(entities)
        }
    }

    @Query("DELETE FROM notification_exceptions")
    suspend fun clearAll()
}
