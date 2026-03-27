package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.data.db.model.SponsorEntity

@Dao
interface SponsorDao {
    @Query("SELECT userId FROM sponsors")
    suspend fun getAllIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SponsorEntity>)

    @Query("DELETE FROM sponsors")
    suspend fun clearAll()

    @Query("DELETE FROM sponsors WHERE userId NOT IN (:ids)")
    suspend fun deleteNotIn(ids: List<Long>)

    @Query("SELECT MAX(updatedAt) FROM sponsors")
    suspend fun getLatestUpdatedAt(): Long?
}
