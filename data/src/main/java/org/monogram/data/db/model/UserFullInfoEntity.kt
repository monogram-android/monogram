package org.monogram.data.db.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_full_info")
data class UserFullInfoEntity(
    @PrimaryKey val userId: Long,
    val bio: String?,
    val commonGroupsCount: Int,
    val giftCount: Int = 0,
    val isBlocked: Boolean,
    val canBeCalled: Boolean,
    val supportsVideoCalls: Boolean,
    val hasPrivateCalls: Boolean,
    val hasPrivateForwards: Boolean,
    val hasRestrictedVoiceAndVideoNoteMessages: Boolean = false,
    val hasPostedToProfileStories: Boolean = false,
    val setChatBackground: Boolean = false,
    val canGetRevenueStatistics: Boolean = false,
    val incomingPaidMessageStarCount: Long = 0L,
    val outgoingPaidMessageStarCount: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)