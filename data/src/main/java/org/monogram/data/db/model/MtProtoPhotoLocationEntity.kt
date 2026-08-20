package org.monogram.data.db.model

import androidx.room.Entity

@Entity(
    tableName = "mtproto_photo_location",
    primaryKeys = ["accountSlot", "environment", "sessionDcId", "photoId", "thumbSize"],
)
data class MtProtoPhotoLocationEntity(
    val accountSlot: String,
    val environment: String,
    val sessionDcId: Int,
    val photoId: Long,
    val thumbSize: String,
    val accessHash: Long,
    val fileReference: ByteArray,
    val photoDcId: Int,
    val width: Int,
    val height: Int,
    val size: Long,
    val updatedAt: Long,
)
