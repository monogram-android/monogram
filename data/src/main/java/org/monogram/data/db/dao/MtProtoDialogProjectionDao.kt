package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.data.db.model.MtProtoDialogProjectionEntity

@Dao
interface MtProtoDialogProjectionDao {
    @Query(
        "SELECT * FROM mtproto_dialog_projection " +
            "WHERE accountSlot = :accountSlot AND environment = :environment AND dcId = :dcId " +
            "ORDER BY pinned DESC, topMessageId DESC, peerType ASC, peerId ASC"
    )
    suspend fun getAll(accountSlot: String, environment: String, dcId: Int): List<MtProtoDialogProjectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MtProtoDialogProjectionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<MtProtoDialogProjectionEntity>)

    @Query("DELETE FROM mtproto_dialog_projection WHERE accountSlot = :accountSlot AND environment = :environment")
    suspend fun deleteAccount(accountSlot: String, environment: String)
}
