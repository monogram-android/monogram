package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.monogram.data.db.model.SearchHistoryEntity

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY timestamp DESC LIMIT 40")
    fun getSearchHistory(): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchChatId(entity: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE chatId = :chatId")
    suspend fun deleteSearchChatId(chatId: Long)

    @Query("DELETE FROM search_history")
    suspend fun clearAll()
}
