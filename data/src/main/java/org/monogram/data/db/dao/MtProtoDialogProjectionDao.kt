package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.data.db.model.MtProtoDialogProjectionEntity

@Dao
interface MtProtoDialogProjectionDao {
    @Query(
        "SELECT dialog.* FROM mtproto_dialog_projection AS dialog " +
            "LEFT JOIN mtproto_message_projection AS message ON " +
            "message.accountSlot = dialog.accountSlot AND message.environment = dialog.environment " +
            "AND message.dcId = dialog.dcId AND message.peerType = dialog.peerType " +
            "AND message.peerId = dialog.peerId AND message.messageId = dialog.topMessageId " +
            "WHERE dialog.accountSlot = :accountSlot AND dialog.environment = :environment AND dialog.dcId = :dcId " +
            "ORDER BY dialog.pinned DESC, COALESCE(message.date, 0) DESC, dialog.topMessageId DESC, " +
            "dialog.peerType ASC, dialog.peerId ASC"
    )
    suspend fun getAll(accountSlot: String, environment: String, dcId: Int): List<MtProtoDialogProjectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MtProtoDialogProjectionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<MtProtoDialogProjectionEntity>)

    @Query("DELETE FROM mtproto_dialog_projection WHERE accountSlot = :accountSlot AND environment = :environment")
    suspend fun deleteAccount(accountSlot: String, environment: String)
}
