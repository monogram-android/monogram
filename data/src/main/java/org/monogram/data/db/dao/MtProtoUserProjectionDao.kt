package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.data.db.model.MtProtoUserProjectionEntity

@Dao
interface MtProtoUserProjectionDao {
    @Query(
        "SELECT * FROM mtproto_user_projection " +
            "WHERE accountSlot = :accountSlot AND environment = :environment AND dcId = :dcId " +
            "AND userId = :userId LIMIT 1"
    )
    suspend fun get(accountSlot: String, environment: String, dcId: Int, userId: Long): MtProtoUserProjectionEntity?

    @Query(
        "SELECT * FROM mtproto_user_projection " +
            "WHERE accountSlot = :accountSlot AND environment = :environment AND dcId = :dcId " +
            "ORDER BY userId ASC"
    )
    suspend fun getAll(accountSlot: String, environment: String, dcId: Int): List<MtProtoUserProjectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MtProtoUserProjectionEntity)

    @Query("DELETE FROM mtproto_user_projection WHERE accountSlot = :accountSlot AND environment = :environment")
    suspend fun deleteAccount(accountSlot: String, environment: String)
}
