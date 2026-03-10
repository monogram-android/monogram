package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.data.db.model.NotificationSettingEntity

@Dao
interface NotificationSettingDao {
    @Query("SELECT * FROM notification_settings")
    suspend fun getAll(): List<NotificationSettingEntity>

    @Query("SELECT * FROM notification_settings WHERE chatId = :chatId")
    suspend fun getByChatId(chatId: Long): NotificationSettingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: NotificationSettingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<NotificationSettingEntity>)

    @Query("DELETE FROM notification_settings WHERE chatId = :chatId")
    suspend fun deleteByChatId(chatId: Long)

    @Query("DELETE FROM notification_settings")
    suspend fun clearAll()
}
