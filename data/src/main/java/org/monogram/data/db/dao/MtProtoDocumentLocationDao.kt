package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.data.db.model.MtProtoDocumentLocationEntity

@Dao
interface MtProtoDocumentLocationDao {
    @Query(
        "SELECT * FROM mtproto_document_location WHERE accountSlot = :accountSlot " +
            "AND environment = :environment AND sessionDcId = :sessionDcId AND documentId = :documentId LIMIT 1"
    )
    suspend fun get(
        accountSlot: String,
        environment: String,
        sessionDcId: Int,
        documentId: Long,
    ): MtProtoDocumentLocationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MtProtoDocumentLocationEntity)

    @Query("DELETE FROM mtproto_document_location WHERE accountSlot = :accountSlot AND environment = :environment")
    suspend fun deleteAccount(accountSlot: String, environment: String)
}
