package org.monogram.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.monogram.core.database.entity.ScheduledMessageEntity
import org.monogram.core.database.entity.ScheduledQueueRevisionEntity

@Dao
abstract class ScheduledMessageDao {
    @Query("SELECT * FROM scheduled_messages WHERE chatId = :chatId ORDER BY date, id")
    abstract fun observe(chatId: Long): Flow<List<ScheduledMessageEntity>>

    @Query("SELECT * FROM scheduled_messages WHERE chatId = :chatId ORDER BY date, id")
    abstract suspend fun forChat(chatId: Long): List<ScheduledMessageEntity>

    @Query("SELECT revision FROM scheduled_queue_revisions WHERE chatId = :chatId")
    protected abstract suspend fun storedRevision(chatId: Long): Long?

    @Transaction
    open suspend fun revision(chatId: Long): Long {
        val stored = storedRevision(chatId)
        if (stored != null) return stored
        insertRevision(ScheduledQueueRevisionEntity(chatId, 0))
        return 0L
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertRows(rows: List<ScheduledMessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertRevision(row: ScheduledQueueRevisionEntity)

    @Query("DELETE FROM scheduled_messages WHERE chatId = :chatId")
    protected abstract suspend fun deleteChat(chatId: Long)

    @Query("DELETE FROM scheduled_messages WHERE chatId = :chatId AND id IN (:ids)")
    protected abstract suspend fun deleteIds(chatId: Long, ids: List<Int>)

    @Query("DELETE FROM scheduled_messages")
    protected abstract suspend fun deleteAllRows()

    private fun validate(chatId: Long, rows: List<ScheduledMessageEntity>) {
        require(rows.all { it.message.chatId == chatId && it.message.id > 0 && !it.message.pending })
        require(rows.map { it.message.id }.distinct().size == rows.size)
    }

    private suspend fun advance(chatId: Long) {
        insertRevision(ScheduledQueueRevisionEntity(chatId, Math.addExact(revision(chatId), 1L)))
    }

    @Transaction
    open suspend fun replaceIfCurrent(
        chatId: Long,
        expectedRevision: Long,
        rows: List<ScheduledMessageEntity>
    ): Boolean {
        validate(chatId, rows)
        if (revision(chatId) != expectedRevision) return false
        deleteChat(chatId)
        insertRows(rows)
        advance(chatId)
        return true
    }

    @Transaction
    open suspend fun applyMessages(chatId: Long, rows: List<ScheduledMessageEntity>) {
        validate(chatId, rows)
        insertRows(rows)
        advance(chatId)
    }

    @Transaction
    open suspend fun remove(chatId: Long, ids: List<Int>) {
        require(ids.all { it > 0 })
        deleteIds(chatId, ids)
        advance(chatId)
    }

    @Transaction
    open suspend fun clear() {
        // Retain and advance revisions so in-flight snapshots cannot repopulate a cleared account.
        for (chatId in trackedChats()) advance(chatId)
        deleteAllRows()
    }

    @Query("SELECT chatId FROM scheduled_queue_revisions")
    protected abstract suspend fun trackedChats(): List<Long>
}
