package org.monogram.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.monogram.data.db.model.MtProtoPhotoLocationEntity

@Dao
interface MtProtoPhotoLocationDao {
    @Query(
        "SELECT * FROM mtproto_photo_location WHERE accountSlot = :accountSlot " +
            "AND environment = :environment AND sessionDcId = :sessionDcId " +
            "AND photoId = :photoId AND thumbSize = :thumbSize LIMIT 1"
    )
    suspend fun get(
        accountSlot: String,
        environment: String,
        sessionDcId: Int,
        photoId: Long,
        thumbSize: String,
    ): MtProtoPhotoLocationEntity?

    @Query(
        "SELECT * FROM mtproto_photo_location WHERE accountSlot = :accountSlot " +
            "AND environment = :environment AND sessionDcId = :sessionDcId AND photoId = :photoId " +
            "ORDER BY (width * height) DESC, size DESC LIMIT 1"
    )
    suspend fun getLargest(
        accountSlot: String,
        environment: String,
        sessionDcId: Int,
        photoId: Long,
    ): MtProtoPhotoLocationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entities: List<MtProtoPhotoLocationEntity>)

    @Query("DELETE FROM mtproto_photo_location WHERE accountSlot = :accountSlot AND environment = :environment")
    suspend fun deleteAccount(accountSlot: String, environment: String)
}
