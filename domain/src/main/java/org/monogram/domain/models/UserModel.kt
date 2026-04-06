package org.monogram.domain.models

data class UserModel(
    val id: Long,
    val firstName: String,
    val lastName: String? = null,
    val username: String? = "",
    val usernames: UsernamesModel? = null,
    val phoneNumber: String? = "",
    val avatarPath: String? = null,
    val personalAvatarPath: String? = null,
    val accentColorId: Int = 0,
    val profileAccentColorId: Int = -1,
    val lastSeen: Long = 0L,
    val isPremium: Boolean = false,
    val isVerified: Boolean = false,
    val isScam: Boolean = false,
    val isFake: Boolean = false,
    val botVerificationIconCustomEmojiId: Long = 0L,
    val isSponsor: Boolean = false,
    val isSupport: Boolean = false,
    val userStatus: UserStatusType = UserStatusType.OFFLINE,
    val statusEmojiId: Long = 0L,
    val statusEmojiPath: String? = null,
    val isContact: Boolean = false,
    val isMutualContact: Boolean = false,
    val isCloseFriend: Boolean = false,
    val type: UserTypeEnum = UserTypeEnum.REGULAR,
    val botTypeInfo: UserTypeBotInfoModel? = null,
    val restrictionInfo: RestrictionInfoModel? = null,
    val activeStoryState: ActiveStoryStateModel? = null,
    val restrictsNewChats: Boolean = false,
    val paidMessageStarCount: Long = 0L,
    val haveAccess: Boolean = true,
    val languageCode: String? = null,
    val backgroundCustomEmojiId: Long = 0L,
    val profileBackgroundCustomEmojiId: Long = 0L,
    val addedToAttachmentMenu: Boolean = false
)

enum class UserStatusType {
    ONLINE, OFFLINE, RECENTLY, LAST_WEEK, LAST_MONTH
}

enum class UserTypeEnum {
    REGULAR, BOT, DELETED, UNKNOWN
}