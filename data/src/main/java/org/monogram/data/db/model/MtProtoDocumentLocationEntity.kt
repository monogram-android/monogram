package org.monogram.data.db.model

import androidx.room.Entity

@Entity(
    tableName = "mtproto_document_location",
    primaryKeys = ["accountSlot", "environment", "sessionDcId", "documentId"],
)
data class MtProtoDocumentLocationEntity(
    val accountSlot: String,
    val environment: String,
    val sessionDcId: Int,
    val documentId: Long,
    val accessHash: Long,
    val fileReference: ByteArray,
    val documentDcId: Int,
    val mimeType: String,
    val size: Long,
    val updatedAt: Long,
)
