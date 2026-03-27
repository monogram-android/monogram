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
    val isSponsor: Boolean = false,
    val isSupport: Boolean = false,
    val userStatus: UserStatusType = UserStatusType.OFFLINE,
    val statusEmojiId: Long = 0L,
    val statusEmojiPath: String? = null,
    val isContact: Boolean = false,
    val isMutualContact: Boolean = false,
    val isCloseFriend: Boolean = false,
    val type: UserTypeEnum = UserTypeEnum.REGULAR,
    val haveAccess: Boolean = true,
    val languageCode: String? = null
)

enum class UserStatusType {
    ONLINE, OFFLINE, RECENTLY, LAST_WEEK, LAST_MONTH
}

enum class UserTypeEnum {
    REGULAR, BOT, DELETED, UNKNOWN
}