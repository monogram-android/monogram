package org.monogram.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.core.database.entity.UpdateCursorEntity

@Dao
interface UpdateCursorDao {
    @Query("SELECT * FROM update_cursors WHERE key = :key LIMIT 1")
    suspend fun get(key: String): UpdateCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cursor: UpdateCursorEntity)

    @Query("DELETE FROM update_cursors")
    suspend fun clear()
}
