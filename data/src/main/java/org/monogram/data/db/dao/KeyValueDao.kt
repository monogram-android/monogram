package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.monogram.data.db.model.KeyValueEntity

@Dao
interface KeyValueDao {
    @Query("SELECT * FROM key_value WHERE `key` = :key")
    suspend fun getValue(key: String): KeyValueEntity?

    @Query("SELECT * FROM key_value WHERE `key` = :key")
    fun observeValue(key: String): Flow<KeyValueEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertValue(entity: KeyValueEntity)

    @Query("DELETE FROM key_value WHERE `key` = :key")
    suspend fun deleteValue(key: String)
}
