package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.data.db.model.MtProtoPollEntity

@Dao
interface MtProtoPollDao {
    @Query(
        "SELECT * FROM mtproto_poll WHERE accountSlot = :accountSlot AND environment = :environment AND pollId = :pollId LIMIT 1"
    )
    suspend fun get(accountSlot: String, environment: String, pollId: Long): MtProtoPollEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MtProtoPollEntity)

    @Query("DELETE FROM mtproto_poll WHERE accountSlot = :accountSlot AND environment = :environment")
    suspend fun deleteAccount(accountSlot: String, environment: String)
}
