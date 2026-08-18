package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.data.db.model.MtProtoCloudObjectEntity

@Dao
interface MtProtoCloudObjectDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(objects: List<MtProtoCloudObjectEntity>): List<Long>

    @Query(
        "SELECT * FROM mtproto_cloud_objects " +
            "WHERE accountSlot = :accountSlot AND environment = :environment AND dcId = :dcId " +
            "ORDER BY sequenceId ASC"
    )
    suspend fun getAll(accountSlot: String, environment: String, dcId: Int): List<MtProtoCloudObjectEntity>

    @Query("DELETE FROM mtproto_cloud_objects WHERE accountSlot = :accountSlot AND environment = :environment")
    suspend fun deleteAccount(accountSlot: String, environment: String)
}
