package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.data.db.model.MtProtoDraftProjectionEntity

@Dao
interface MtProtoDraftProjectionDao {
    @Query(
        "SELECT text FROM mtproto_draft_projection WHERE accountSlot = :accountSlot " +
            "AND environment = :environment AND dcId = :dcId AND peerType = :peerType AND peerId = :peerId"
    )
    suspend fun get(
        accountSlot: String,
        environment: String,
        dcId: Int,
        peerType: String,
        peerId: Long,
    ): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MtProtoDraftProjectionEntity)

    @Query("DELETE FROM mtproto_draft_projection WHERE accountSlot = :accountSlot AND environment = :environment")
    suspend fun deleteAccount(accountSlot: String, environment: String)
}
