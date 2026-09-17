package org.monogram.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.core.database.entity.MetaEntity

@Dao
interface MetaDao {
    @Query("SELECT * FROM meta WHERE key = :key LIMIT 1")
    suspend fun get(key: String): MetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MetaEntity)

    @Query("DELETE FROM meta WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM meta WHERE `key` LIKE :pattern")
    suspend fun deleteLike(pattern: String)

    @Query("DELETE FROM meta")
    suspend fun clear()
}
