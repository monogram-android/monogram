package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.data.db.model.ChatFullInfoEntity

@Dao
interface ChatFullInfoDao {
    @Query("SELECT * FROM chat_full_info WHERE chatId = :chatId")
    suspend fun getChatFullInfo(chatId: Long): ChatFullInfoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatFullInfo(info: ChatFullInfoEntity)

    @Query("DELETE FROM chat_full_info WHERE chatId = :chatId")
    suspend fun deleteChatFullInfo(chatId: Long)

    @Query("DELETE FROM chat_full_info WHERE createdAt < :timestamp")
    suspend fun deleteExpired(timestamp: Long)
}