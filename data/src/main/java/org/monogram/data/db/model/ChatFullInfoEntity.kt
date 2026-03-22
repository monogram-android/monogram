package org.monogram.data.db.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_full_info")
data class ChatFullInfoEntity(
    @PrimaryKey val chatId: Long,
    val description: String?,
    val inviteLink: String?,
    val memberCount: Int,
    val onlineCount: Int,
    val administratorCount: Int,
    val restrictedCount: Int,
    val bannedCount: Int,
    val commonGroupsCount: Int,
    val giftCount: Int = 0,
    val isBlocked: Boolean,
    val botInfo: String?,
    val slowModeDelay: Int,
    val locationAddress: String?,
    val canSetStickerSet: Boolean,
    val canSetLocation: Boolean,
    val canGetMembers: Boolean,
    val canGetStatistics: Boolean,
    val canGetRevenueStatistics: Boolean = false,
    val linkedChatId: Long,
    val note: String?,
    val canBeCalled: Boolean,
    val supportsVideoCalls: Boolean,
    val hasPrivateCalls: Boolean,
    val hasPrivateForwards: Boolean,
    val hasRestrictedVoiceAndVideoNoteMessages: Boolean = false,
    val hasPostedToProfileStories: Boolean = false,
    val setChatBackground: Boolean = false,
    val incomingPaidMessageStarCount: Long = 0L,
    val outgoingPaidMessageStarCount: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)