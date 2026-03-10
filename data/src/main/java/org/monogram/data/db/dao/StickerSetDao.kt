package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.monogram.data.db.model.StickerSetEntity

@Dao
interface StickerSetDao {
    @Query("SELECT * FROM sticker_sets WHERE type = :type AND isInstalled = 1 AND isArchived = 0 ORDER BY createdAt DESC")
    fun getInstalledStickerSetsByType(type: String): Flow<List<StickerSetEntity>>

    @Query("SELECT * FROM sticker_sets WHERE type = :type AND isArchived = 1 ORDER BY createdAt DESC")
    fun getArchivedStickerSetsByType(type: String): Flow<List<StickerSetEntity>>

    @Query("SELECT * FROM sticker_sets WHERE id = :id")
    suspend fun getStickerSetById(id: Long): StickerSetEntity?

    @Query("SELECT * FROM sticker_sets WHERE name = :name")
    suspend fun getStickerSetByName(name: String): StickerSetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStickerSets(sets: List<StickerSetEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStickerSet(set: StickerSetEntity)

    @Query("DELETE FROM sticker_sets WHERE type = :type AND isInstalled = :isInstalled AND isArchived = :isArchived")
    suspend fun deleteStickerSets(type: String, isInstalled: Boolean, isArchived: Boolean)

    @Query("DELETE FROM sticker_sets")
    suspend fun clearAll()
}
