package org.monogram.data.db.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "mtproto_pending_envelopes",
    indices = [
        Index(
            value = ["accountSlot", "environment", "dcId", "payloadHash"],
            unique = true,
        ),
        Index(value = ["accountSlot", "environment", "dcId", "sequenceId"]),
    ],
)
data class MtProtoPendingEnvelopeEntity(
    @PrimaryKey(autoGenerate = true) val sequenceId: Long = 0,
    val accountSlot: String,
    val environment: String,
    val dcId: Int,
    val payloadHash: String,
    val payload: ByteArray,
    val createdAt: Long,
)
