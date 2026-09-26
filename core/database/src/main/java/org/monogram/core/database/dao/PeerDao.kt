package org.monogram.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.core.database.entity.PeerEntity

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): PeerEntity?

    @Query("SELECT * FROM peers WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<PeerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(peers: List<PeerEntity>)

    @Query("UPDATE peers SET status = :status, statusAt = :at WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String?, at: Long?)

    @Query("UPDATE peers SET emojiStatusDocumentId = :documentId WHERE id = :id")
    suspend fun updateEmojiStatus(id: Long, documentId: Long?)

    @Query("DELETE FROM peers")
    suspend fun clear()
}
