package org.monogram.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.core.database.entity.SponsorEntity

@Dao
interface SponsorDao {
    @Query("SELECT userId FROM sponsors")
    suspend fun getAllIds(): List<Long>

    @Query("SELECT updatedAt FROM sponsors ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestUpdatedAt(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<SponsorEntity>)

    @Query("DELETE FROM sponsors")
    suspend fun clear()
}
