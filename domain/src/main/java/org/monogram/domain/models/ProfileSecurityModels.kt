package org.monogram.domain.models

data class RestrictionInfoModel(
    val restrictionReason: String? = null,
    val hasSensitiveContent: Boolean = false
)

enum class ActiveStoryStateType {
    LIVE,
    UNREAD,
    READ,
    UNKNOWN
}

data class ActiveStoryStateModel(
    val type: ActiveStoryStateType = ActiveStoryStateType.UNKNOWN,
    val storyId: Int = 0
)

data class BotVerificationModel(
    val botUserId: Long = 0L,
    val iconCustomEmojiId: Long = 0L,
    val customDescription: String? = null
)

data class BotVerificationParametersModel(
    val iconCustomEmojiId: Long = 0L,
    val organizationName: String? = null,
    val defaultCustomDescription: String? = null,
    val canSetCustomDescription: Boolean = false
)

enum class ProfileTabType {
    POSTS,
    GIFTS,
    MEDIA,
    FILES,
    LINKS,
    MUSIC,
    VOICE,
    GIFS,
    UNKNOWN
}

data class ProfileAudioModel(
    val duration: Int = 0,
    val title: String? = null,
    val performer: String? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
    val fileId: Int = 0,
    val filePath: String? = null
)

data class UserRatingModel(
    val level: Int = 0,
    val isMaximumLevelReached: Boolean = false,
    val rating: Long = 0L,
    val currentLevelRating: Long = 0L,
    val nextLevelRating: Long = 0L
)

data class ChatAdministratorRightsModel(
    val canManageChat: Boolean = false,
    val canChangeInfo: Boolean = false,
    val canPostMessages: Boolean = false,
    val canEditMessages: Boolean = false,
    val canDeleteMessages: Boolean = false,
    val canInviteUsers: Boolean = false,
    val canRestrictMembers: Boolean = false,
    val canPinMessages: Boolean = false,
    val canManageTopics: Boolean = false,
    val canPromoteMembers: Boolean = false,
    val canManageVideoChats: Boolean = false,
    val canPostStories: Boolean = false,
    val canEditStories: Boolean = false,
    val canDeleteStories: Boolean = false,
    val canManageDirectMessages: Boolean = false,
    val canManageTags: Boolean = false,
    val isAnonymous: Boolean = false
)

data class AffiliateProgramInfoModel(
    val commissionPerMille: Int = 0,
    val monthCount: Int = 0,
    val endDate: Int = 0,
    val dailyRevenuePerUserStarCount: Long = 0L,
    val dailyRevenuePerUserNanostarCount: Int = 0
)

data class UserTypeBotInfoModel(
    val canBeEdited: Boolean = false,
    val canJoinGroups: Boolean = false,
    val canReadAllGroupMessages: Boolean = false,
    val hasMainWebApp: Boolean = false,
    val hasTopics: Boolean = false,
    val allowsUsersToCreateTopics: Boolean = false,
    val canManageBots: Boolean = false,
    val isInline: Boolean = false,
    val inlineQueryPlaceholder: String? = null,
    val needLocation: Boolean = false,
    val canConnectToBusiness: Boolean = false,
    val canBeAddedToAttachmentMenu: Boolean = false,
    val activeUserCount: Int = 0
)

data class SupergroupBotCommandsModel(
    val botUserId: Long = 0L,
    val commands: List<BotCommandModel> = emptyList()
)
