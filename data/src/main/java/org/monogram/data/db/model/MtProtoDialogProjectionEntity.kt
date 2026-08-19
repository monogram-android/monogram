package org.monogram.data.db.model

import androidx.room.Entity

@Entity(
    tableName = "mtproto_dialog_projection",
    primaryKeys = ["accountSlot", "environment", "dcId", "peerType", "peerId"],
)
data class MtProtoDialogProjectionEntity(
    val accountSlot: String,
    val environment: String,
    val dcId: Int,
    val peerType: String,
    val peerId: Long,
    val pinned: Boolean,
    val unreadMark: Boolean,
    val topMessageId: Int,
    val unreadCount: Int,
    val unreadMentionsCount: Int,
    val unreadReactionsCount: Int,
    val folderId: Int?,
    val updatedAt: Long,
)
