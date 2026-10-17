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

    @Query(
        """
        SELECT id FROM peers
        WHERE id NOT IN (SELECT id FROM chats)
        AND id NOT IN (SELECT senderId FROM messages WHERE senderId IS NOT NULL)
        AND id NOT IN (SELECT peerId FROM profile_tabs)
        AND id NOT IN (SELECT peerId FROM profile_members)
        AND id NOT IN (SELECT peerId FROM profile_media)
        AND id NOT IN (SELECT peerId FROM profile_common)
        LIMIT :limit
        """,
    )
    suspend fun unreferencedIds(limit: Int): List<Long>

    @Query("DELETE FROM peers WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("DELETE FROM peers")
    suspend fun clear()
}
