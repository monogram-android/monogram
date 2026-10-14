package org.monogram.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.core.database.entity.UpdateBatchReceiptEntity
import org.monogram.core.database.entity.UpdateCursorEntity

@Dao
interface UpdateCursorDao {
    @Query("SELECT * FROM update_cursors WHERE key = :key LIMIT 1")
    suspend fun get(key: String): UpdateCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cursor: UpdateCursorEntity)

    @Query("DELETE FROM update_cursors")
    suspend fun clear()

    @Query(
        "SELECT EXISTS(SELECT 1 FROM update_batch_receipts " +
                "WHERE accountGeneration = :generation AND batchId = :batchId)"
    )
    suspend fun isBatchApplied(generation: Long, batchId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReceipt(receipt: UpdateBatchReceiptEntity): Long

    /** Keeps a bounded window of the current account and drops receipts of earlier accounts. */
    @Query(
        "DELETE FROM update_batch_receipts WHERE accountGeneration != :generation " +
                "OR (accountGeneration = :generation AND batchId <= :upToBatchId)"
    )
    suspend fun pruneReceipts(generation: Long, upToBatchId: Long)

    @Query("DELETE FROM update_batch_receipts")
    suspend fun clearReceipts()
}
