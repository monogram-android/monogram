package org.monogram.data.db.model

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "mtproto_message_projection",
    primaryKeys = ["accountSlot", "environment", "dcId", "peerType", "peerId", "messageId"],
    indices = [
        Index(value = ["accountSlot", "environment", "dcId", "peerType", "peerId", "date", "messageId"]),
    ],
)
data class MtProtoMessageProjectionEntity(
    val accountSlot: String,
    val environment: String,
    val dcId: Int,
    val peerType: String,
    val peerId: Long,
    val messageId: Int,
    val senderType: String?,
    val senderId: Long?,
    val date: Int,
    val text: String?,
    val isService: Boolean,
    val isDeleted: Boolean,
    val isOutgoing: Boolean,
    val isMentioned: Boolean,
    val isMediaUnread: Boolean,
    val isSilent: Boolean,
    val isPinned: Boolean,
    val editDate: Int?,
    val groupedId: Long?,
    val hasMedia: Boolean,
    val documentId: Long? = null,
    val photoId: Long? = null,
    val isScheduled: Boolean = false,
    val updatedAt: Long,
)
