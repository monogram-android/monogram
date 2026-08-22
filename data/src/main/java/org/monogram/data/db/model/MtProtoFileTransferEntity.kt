package org.monogram.data.db.model

import androidx.room.Entity

@Entity(
    tableName = "mtproto_file_transfer",
    primaryKeys = ["accountSlot", "environment", "dcId", "fileKey"],
)
data class MtProtoFileTransferEntity(
    val accountSlot: String,
    val environment: String,
    val dcId: Int,
    val fileKey: String,
    val path: String,
    val expectedSize: Long,
    val committedOffset: Long,
    val isComplete: Boolean,
    val updatedAt: Long,
)
