package org.monogram.data.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.monogram.data.db.model.TextCompositionStyleEntity

@Dao
interface TextCompositionStyleDao {
    @Query("SELECT * FROM text_composition_styles")
    fun getAll(): Flow<List<TextCompositionStyleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(styles: List<TextCompositionStyleEntity>)

    @Query("DELETE FROM text_composition_styles")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceAll(styles: List<TextCompositionStyleEntity>) {
        clearAll()
        if (styles.isNotEmpty()) {
            insertAll(styles)
        }
    }
}
