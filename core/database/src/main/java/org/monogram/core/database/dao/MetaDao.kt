package org.monogram.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.monogram.core.database.entity.MetaEntity

@Dao
interface MetaDao {
    @Query("SELECT * FROM meta WHERE key = :key LIMIT 1")
    suspend fun get(key: String): MetaEntity?

    @Query("SELECT * FROM meta WHERE `key` = :key LIMIT 1")
    fun observe(key: String): Flow<MetaEntity?>

    @Query("SELECT * FROM meta WHERE `key` LIKE :pattern")
    fun observeLike(pattern: String): Flow<List<MetaEntity>>

    @Query("SELECT * FROM meta WHERE `key` LIKE 'cloud_draft:%' OR `key` LIKE 'draft:%'")
    fun observeDrafts(): Flow<List<MetaEntity>>

    @Query("SELECT * FROM meta WHERE `key` LIKE 'cloud_draft:%' OR `key` LIKE 'draft:%'")
    suspend fun drafts(): List<MetaEntity>

    @Query("SELECT * FROM meta WHERE `key` = :modernKey OR `key` = :legacyKey")
    fun observeDraft(modernKey: String, legacyKey: String): Flow<List<MetaEntity>>

    @Query("SELECT `key` FROM meta WHERE `key` LIKE :pattern")
    suspend fun keysLike(pattern: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MetaEntity)

    @Query("DELETE FROM meta WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM meta WHERE `key` LIKE :pattern")
    suspend fun deleteLike(pattern: String)

    @Query("DELETE FROM meta WHERE substr(`key`, 1, length(:prefix)) = :prefix")
    suspend fun deletePrefix(prefix: String)

    @Query("DELETE FROM meta")
    suspend fun clear()
}
