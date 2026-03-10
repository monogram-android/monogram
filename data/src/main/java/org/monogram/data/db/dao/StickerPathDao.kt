package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.data.db.model.StickerPathEntity

@Dao
interface StickerPathDao {
    @Query("SELECT path FROM sticker_paths WHERE fileId = :fileId")
    suspend fun getPath(fileId: Long): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPath(entity: StickerPathEntity)

    @Query("DELETE FROM sticker_paths")
    suspend fun clearAll()
}