package org.monogram.data.db.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "mtproto_file_handle",
    indices = [
        Index(
            value = ["accountSlot", "environment", "sessionDcId", "documentId"],
            unique = true,
        ),
    ],
)
data class MtProtoFileHandleEntity(
    @PrimaryKey(autoGenerate = true)
    val fileId: Int = 0,
    val accountSlot: String,
    val environment: String,
    val sessionDcId: Int,
    val documentId: Long,
)
