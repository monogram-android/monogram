package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.data.db.model.MtProtoFileTransferEntity

@Dao
interface MtProtoFileTransferDao {
    @Query(
        "SELECT * FROM mtproto_file_transfer WHERE accountSlot = :accountSlot " +
            "AND environment = :environment AND dcId = :dcId AND fileKey = :fileKey LIMIT 1"
    )
    suspend fun get(
        accountSlot: String,
        environment: String,
        dcId: Int,
        fileKey: String,
    ): MtProtoFileTransferEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MtProtoFileTransferEntity)

    @Query("DELETE FROM mtproto_file_transfer WHERE accountSlot = :accountSlot AND environment = :environment")
    suspend fun deleteAccount(accountSlot: String, environment: String)
}
