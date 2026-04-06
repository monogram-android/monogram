package org.monogram.data.db.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "users",
    indices = [
        Index(value = ["username"]),
        Index(value = ["createdAt"])
    ]
)
data class UserEntity(
    @PrimaryKey val id: Long,
    val firstName: String,
    val lastName: String?,
    val phoneNumber: String?,
    val avatarPath: String?,
    val personalAvatarPath: String? = null,
    val isPremium: Boolean,
    val isVerified: Boolean,
    val isScam: Boolean = false,
    val isFake: Boolean = false,
    val botVerificationIconCustomEmojiId: Long = 0L,
    val isSupport: Boolean = false,
    val isContact: Boolean = false,
    val isMutualContact: Boolean = false,
    val isCloseFriend: Boolean = false,
    val botTypeCanBeEdited: Boolean = false,
    val botTypeCanJoinGroups: Boolean = false,
    val botTypeCanReadAllGroupMessages: Boolean = false,
    val botTypeHasMainWebApp: Boolean = false,
    val botTypeHasTopics: Boolean = false,
    val botTypeAllowsUsersToCreateTopics: Boolean = false,
    val botTypeCanManageBots: Boolean = false,
    val botTypeIsInline: Boolean = false,
    val botTypeInlineQueryPlaceholder: String? = null,
    val botTypeNeedLocation: Boolean = false,
    val botTypeCanConnectToBusiness: Boolean = false,
    val botTypeCanBeAddedToAttachmentMenu: Boolean = false,
    val botTypeActiveUserCount: Int = 0,
    val userType: String = "UNKNOWN",
    val restrictionReason: String? = null,
    val hasSensitiveContent: Boolean = false,
    val activeStoryStateType: String? = null,
    val activeStoryId: Int = 0,
    val restrictsNewChats: Boolean = false,
    val paidMessageStarCount: Long = 0L,
    val haveAccess: Boolean = true,
    val username: String?,
    val usernamesData: String? = null,
    val statusType: String = "OFFLINE",
    val accentColorId: Int = 0,
    val backgroundCustomEmojiId: Long = 0L,
    val profileAccentColorId: Int = -1,
    val profileBackgroundCustomEmojiId: Long = 0L,
    val statusEmojiId: Long = 0L,
    val languageCode: String? = null,
    val addedToAttachmentMenu: Boolean = false,
    val lastSeen: Long,
    val createdAt: Long = System.currentTimeMillis()
)