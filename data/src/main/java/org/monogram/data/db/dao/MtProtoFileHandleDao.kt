package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.data.db.model.MtProtoFileHandleEntity

@Dao
interface MtProtoFileHandleDao {
    @Query(
        "SELECT * FROM mtproto_file_handle WHERE accountSlot = :accountSlot " +
            "AND environment = :environment AND sessionDcId = :sessionDcId " +
            "AND documentId = :documentId LIMIT 1"
    )
    suspend fun getByDocument(
        accountSlot: String,
        environment: String,
        sessionDcId: Int,
        documentId: Long,
    ): MtProtoFileHandleEntity?

    @Query(
        "SELECT * FROM mtproto_file_handle WHERE fileId = :fileId AND accountSlot = :accountSlot " +
            "AND environment = :environment AND sessionDcId = :sessionDcId LIMIT 1"
    )
    suspend fun get(
        fileId: Int,
        accountSlot: String,
        environment: String,
        sessionDcId: Int,
    ): MtProtoFileHandleEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: MtProtoFileHandleEntity): Long

    @Query("DELETE FROM mtproto_file_handle WHERE accountSlot = :accountSlot AND environment = :environment")
    suspend fun deleteAccount(accountSlot: String, environment: String)
}
