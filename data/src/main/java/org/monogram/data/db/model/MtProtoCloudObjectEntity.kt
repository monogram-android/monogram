package org.monogram.data.db.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "mtproto_cloud_objects",
    indices = [
        Index(
            value = ["accountSlot", "environment", "dcId", "objectType", "payloadHash"],
            unique = true,
        ),
        Index(value = ["accountSlot", "environment", "dcId", "sequenceId"]),
    ],
)
data class MtProtoCloudObjectEntity(
    @PrimaryKey(autoGenerate = true) val sequenceId: Long = 0,
    val accountSlot: String,
    val environment: String,
    val dcId: Int,
    val objectType: String,
    val payloadHash: String,
    val payload: ByteArray,
    val createdAt: Long,
)
