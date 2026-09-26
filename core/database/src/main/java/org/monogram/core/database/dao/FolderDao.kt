package org.monogram.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.core.database.entity.FolderEntity

@Dao
interface FolderDao {
    /** Position first; the id tiebreak keeps pre-v31 rows in their old order. */
    @Query("SELECT * FROM folders ORDER BY position ASC, id ASC")
    suspend fun observeAll(): List<FolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(folders: List<FolderEntity>)

    @Query("DELETE FROM folders")
    suspend fun clear()
}
