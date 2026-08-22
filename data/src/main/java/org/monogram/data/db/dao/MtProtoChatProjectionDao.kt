package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.data.db.model.MtProtoChatProjectionEntity

@Dao
interface MtProtoChatProjectionDao {
    @Query(
        "SELECT * FROM mtproto_chat_projection " +
            "WHERE accountSlot = :accountSlot AND environment = :environment AND dcId = :dcId " +
            "AND chatId = :chatId LIMIT 1"
    )
    suspend fun get(accountSlot: String, environment: String, dcId: Int, chatId: Long): MtProtoChatProjectionEntity?

    @Query(
        "SELECT * FROM mtproto_chat_projection " +
            "WHERE accountSlot = :accountSlot AND environment = :environment AND dcId = :dcId " +
            "ORDER BY chatId ASC"
    )
    suspend fun getAll(accountSlot: String, environment: String, dcId: Int): List<MtProtoChatProjectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MtProtoChatProjectionEntity)

    @Query("DELETE FROM mtproto_chat_projection WHERE accountSlot = :accountSlot AND environment = :environment")
    suspend fun deleteAccount(accountSlot: String, environment: String)
}
