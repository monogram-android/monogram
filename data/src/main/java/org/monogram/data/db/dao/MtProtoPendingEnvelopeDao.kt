package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.data.db.model.MtProtoPendingEnvelopeEntity

@Dao
interface MtProtoPendingEnvelopeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(envelope: MtProtoPendingEnvelopeEntity): Long

    @Query(
        "SELECT * FROM mtproto_pending_envelopes " +
            "WHERE accountSlot = :accountSlot AND environment = :environment AND dcId = :dcId " +
            "AND payloadHash = :payloadHash LIMIT 1"
    )
    suspend fun getByHash(
        accountSlot: String,
        environment: String,
        dcId: Int,
        payloadHash: String,
    ): MtProtoPendingEnvelopeEntity?

    @Query(
        "SELECT * FROM mtproto_pending_envelopes " +
            "WHERE accountSlot = :accountSlot AND environment = :environment AND dcId = :dcId " +
            "ORDER BY sequenceId ASC"
    )
    suspend fun getPending(accountSlot: String, environment: String, dcId: Int): List<MtProtoPendingEnvelopeEntity>

    @Query("DELETE FROM mtproto_pending_envelopes WHERE sequenceId = :sequenceId")
    suspend fun delete(sequenceId: Long)

    @Query("DELETE FROM mtproto_pending_envelopes WHERE accountSlot = :accountSlot AND environment = :environment")
    suspend fun deleteAccount(accountSlot: String, environment: String)
}
