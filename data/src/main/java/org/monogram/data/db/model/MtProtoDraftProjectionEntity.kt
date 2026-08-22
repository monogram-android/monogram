package org.monogram.data.db.model

import androidx.room.Entity

@Entity(
    tableName = "mtproto_draft_projection",
    primaryKeys = ["accountSlot", "environment", "dcId", "peerType", "peerId"],
)
data class MtProtoDraftProjectionEntity(
    val accountSlot: String,
    val environment: String,
    val dcId: Int,
    val peerType: String,
    val peerId: Long,
    val text: String,
    val updatedAt: Long,
)
