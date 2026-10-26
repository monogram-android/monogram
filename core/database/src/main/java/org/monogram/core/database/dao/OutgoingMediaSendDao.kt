package org.monogram.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.monogram.core.database.entity.OutgoingMediaBatchEntity
import org.monogram.core.database.entity.OutgoingMediaItemEntity
import org.monogram.core.database.entity.OutgoingMediaSend
import org.monogram.core.database.entity.OutgoingSendPhase

@Dao
abstract class OutgoingMediaSendDao {
    @Transaction
    @Query("SELECT * FROM outgoing_media_batches ORDER BY createdAt, batchId")
    abstract fun observeAll(): Flow<List<OutgoingMediaSend>>

    @Transaction
    @Query("SELECT * FROM outgoing_media_batches ORDER BY createdAt, batchId")
    abstract suspend fun all(): List<OutgoingMediaSend>

    @Transaction
    @Query("SELECT * FROM outgoing_media_batches WHERE batchId = :batchId")
    abstract suspend fun get(batchId: Long): OutgoingMediaSend?

    @Query("SELECT * FROM outgoing_media_items WHERE randomId = :randomId")
    abstract suspend fun item(randomId: Long): OutgoingMediaItemEntity?

    @Insert
    protected abstract suspend fun insertBatch(batch: OutgoingMediaBatchEntity)

    @Insert
    protected abstract suspend fun insertItems(items: List<OutgoingMediaItemEntity>)

    @Transaction
    open suspend fun enqueue(send: OutgoingMediaSend) {
        send.requireValid()
        val previous = get(send.batch.batchId)
        if (previous != null) {
            require(
                previous.batch.copy(phase = OutgoingSendPhase.READY) == send.batch &&
                    previous.items.sortedBy { it.position } == send.items.sortedBy { it.position })
            return
        }
        insertBatch(send.batch)
        insertItems(send.items)
    }

    @Query("UPDATE outgoing_media_batches SET phase = 'IN_FLIGHT' WHERE batchId = :batchId AND phase = 'READY'")
    abstract suspend fun claim(batchId: Long): Int

    @Query("UPDATE outgoing_media_batches SET phase = 'UNCERTAIN' WHERE phase = 'IN_FLIGHT'")
    abstract suspend fun recoverInterrupted(): Int

    @Query("UPDATE outgoing_media_batches SET phase = 'UNCERTAIN' WHERE batchId = :batchId AND phase = 'IN_FLIGHT'")
    abstract suspend fun markUncertain(batchId: Long): Int

    @Query("UPDATE outgoing_media_batches SET phase = 'FAILED' WHERE batchId = :batchId AND phase = 'IN_FLIGHT'")
    abstract suspend fun markFailed(batchId: Long): Int

    @Query("UPDATE outgoing_media_batches SET phase = 'READY' WHERE batchId = :batchId AND phase IN ('UNCERTAIN', 'FAILED')")
    abstract suspend fun retry(batchId: Long): Int

    // Keep every source until the complete album has authoritative receipts.
    @Query("DELETE FROM outgoing_media_batches WHERE batchId = :batchId")
    protected abstract suspend fun deleteBatch(batchId: Long)

    @Transaction
    open suspend fun acknowledge(batchId: Long, chatId: Long, randomIds: Set<Long>) {
        val send = get(batchId) ?: return
        require(send.batch.chatId == chatId && send.items.map { it.randomId }.toSet() == randomIds)
        deleteBatch(batchId)
    }

    @Query("DELETE FROM outgoing_media_batches")
    abstract suspend fun clear()
}
