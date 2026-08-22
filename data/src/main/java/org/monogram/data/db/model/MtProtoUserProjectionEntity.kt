package org.monogram.data.db.model

import androidx.room.Entity

@Entity(
    tableName = "mtproto_user_projection",
    primaryKeys = ["accountSlot", "environment", "dcId", "userId"],
)
data class MtProtoUserProjectionEntity(
    val accountSlot: String,
    val environment: String,
    val dcId: Int,
    val userId: Long,
    val accessHash: Long?,
    val firstName: String?,
    val lastName: String?,
    val username: String?,
    val phone: String?,
    val isSelf: Boolean,
    val isContact: Boolean,
    val isMutualContact: Boolean,
    val isDeleted: Boolean,
    val isBot: Boolean,
    val isVerified: Boolean,
    val isRestricted: Boolean,
    val isScam: Boolean,
    val isFake: Boolean,
    val isPremium: Boolean,
    val isMin: Boolean,
    val updatedAt: Long,
)
