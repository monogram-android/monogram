package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.monogram.data.db.model.AttachBotEntity

@Dao
interface AttachBotDao {
    @Query("SELECT * FROM attach_bots")
    fun getAttachBots(): Flow<List<AttachBotEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachBots(bots: List<AttachBotEntity>)

    @Query("DELETE FROM attach_bots")
    suspend fun clearAll()
}
