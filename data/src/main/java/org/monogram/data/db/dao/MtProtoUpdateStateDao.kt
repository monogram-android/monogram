package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.data.db.model.MtProtoUpdateStateEntity

@Dao
interface MtProtoUpdateStateDao {
    @Query(
        "SELECT * FROM mtproto_update_state " +
            "WHERE accountSlot = :accountSlot AND environment = :environment AND dcId = :dcId"
    )
    suspend fun get(accountSlot: String, environment: String, dcId: Int): MtProtoUpdateStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: MtProtoUpdateStateEntity)

    @Query(
        "DELETE FROM mtproto_update_state " +
            "WHERE accountSlot = :accountSlot AND environment = :environment AND dcId = :dcId"
    )
    suspend fun delete(accountSlot: String, environment: String, dcId: Int)

    @Query("DELETE FROM mtproto_update_state WHERE accountSlot = :accountSlot AND environment = :environment")
    suspend fun deleteAccount(accountSlot: String, environment: String)
}
