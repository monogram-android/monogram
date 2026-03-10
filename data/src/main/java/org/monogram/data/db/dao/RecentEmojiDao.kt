package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.monogram.data.db.model.RecentEmojiEntity

@Dao
interface RecentEmojiDao {
    @Query("SELECT * FROM recent_emojis ORDER BY timestamp DESC LIMIT 50")
    fun getRecentEmojis(): Flow<List<RecentEmojiEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecentEmoji(emoji: RecentEmojiEntity)

    @Query("DELETE FROM recent_emojis WHERE emoji = :emoji AND stickerId = :stickerId")
    suspend fun deleteRecentEmoji(emoji: String, stickerId: Long?)

    @Query("DELETE FROM recent_emojis")
    suspend fun clearAll()
}
