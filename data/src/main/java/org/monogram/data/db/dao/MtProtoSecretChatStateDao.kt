package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.data.db.model.MtProtoSecretChatStateEntity

@Dao
interface MtProtoSecretChatStateDao {
    @Query(
        "SELECT * FROM mtproto_secret_chat_state WHERE accountSlot = :accountSlot AND environment = :environment AND chatId = :chatId LIMIT 1"
    )
    suspend fun get(accountSlot: String, environment: String, chatId: Int): MtProtoSecretChatStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MtProtoSecretChatStateEntity)

    @Query(
        "DELETE FROM mtproto_secret_chat_state WHERE accountSlot = :accountSlot AND environment = :environment AND chatId = :chatId"
    )
    suspend fun delete(accountSlot: String, environment: String, chatId: Int)

    @Query("DELETE FROM mtproto_secret_chat_state WHERE accountSlot = :accountSlot AND environment = :environment")
    suspend fun deleteAccount(accountSlot: String, environment: String)
}
