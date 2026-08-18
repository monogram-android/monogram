package org.monogram.data.db.model

import androidx.room.Entity

@Entity(
    tableName = "mtproto_chat_projection",
    primaryKeys = ["accountSlot", "environment", "dcId", "chatId"],
)
data class MtProtoChatProjectionEntity(
    val accountSlot: String,
    val environment: String,
    val dcId: Int,
    val chatId: Long,
    val type: String,
    val accessHash: Long?,
    val title: String?,
    val username: String?,
    val participantsCount: Int?,
    val isDeleted: Boolean,
    val isForbidden: Boolean,
    val isLeft: Boolean,
    val isDeactivated: Boolean,
    val isBroadcast: Boolean,
    val isMegagroup: Boolean,
    val isVerified: Boolean,
    val isRestricted: Boolean,
    val isScam: Boolean,
    val isFake: Boolean,
    val isForum: Boolean,
    val isMin: Boolean,
    val updatedAt: Long,
)
