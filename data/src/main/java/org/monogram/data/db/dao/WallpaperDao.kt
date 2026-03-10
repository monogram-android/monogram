package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.monogram.data.db.model.WallpaperEntity

@Dao
interface WallpaperDao {
    @Query("SELECT * FROM wallpapers")
    fun getWallpapers(): Flow<List<WallpaperEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWallpapers(wallpapers: List<WallpaperEntity>)

    @Query("DELETE FROM wallpapers")
    suspend fun clearAll()
}
