package org.monogram.data.mapper.user

import org.drinkless.tdlib.TdApi
import org.monogram.data.mapper.isForcedVerifiedUser
import org.monogram.data.mapper.isSponsoredUser
import org.monogram.data.mapper.isValidFilePath
import org.monogram.domain.models.*

fun TdApi.User.toDomain(
    fullInfo: TdApi.UserFullInfo? = null,
    customEmojiPath: String? = null
): UserModel {
    val emojiStatusId = this.getEmojiStatusId()
    val username = usernames?.activeUsernames?.firstOrNull()

    val personalAvatarPath = fullInfo?.personalPhoto?.let { personalPhoto ->
        val bestPhotoSize = personalPhoto.sizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: personalPhoto.sizes.lastOrNull()
        personalPhoto.animation?.file?.local?.path?.takeIf { isValidFilePath(it) }
            ?: bestPhotoSize?.photo?.local?.path?.takeIf { isValidFilePath(it) }
    }

    val lastSeen = (status as? TdApi.UserStatusOffline)
        ?.wasOnline?.toLong()?.times(1000L) ?: 0L

    return UserModel(
        id = id,
        firstName = firstName,
        lastName = lastName.ifEmpty { null },
        phoneNumber = phoneNumber.ifEmpty { null },
        avatarPath = this.resolveAvatarPath(),
        personalAvatarPath = personalAvatarPath,
        isPremium = isPremium,
        isVerified = (verificationStatus?.isVerified ?: false) || isForcedVerifiedUser(id),
        isScam = verificationStatus?.isScam ?: false,
        isFake = verificationStatus?.isFake ?: false,
        botVerificationIconCustomEmojiId = verificationStatus?.botVerificationIconCustomEmojiId ?: 0L,
        isSponsor = isSponsoredUser(id),
        isSupport = isSupport,
        type = type.toDomain(),
        botTypeInfo = (type as? TdApi.UserTypeBot)?.toDomain(),
        restrictionInfo = restrictionInfo?.toDomain(),
        activeStoryState = activeStoryState?.toDomain(),
        restrictsNewChats = restrictsNewChats,
        paidMessageStarCount = paidMessageStarCount,
        statusEmojiId = emojiStatusId,
        statusEmojiPath = customEmojiPath,
        username = username,
        usernames = usernames?.let { usernames!!.toDomain() },
        userStatus = if (type is TdApi.UserTypeBot) UserStatusType.OFFLINE
        else status.toDomain(),
        isContact = isContact,
        isMutualContact = isMutualContact,
        isCloseFriend = isCloseFriend,
        haveAccess = haveAccess,
        languageCode = languageCode,
        lastSeen = lastSeen,
        backgroundCustomEmojiId = backgroundCustomEmojiId,
        profileBackgroundCustomEmojiId = profileBackgroundCustomEmojiId,
        addedToAttachmentMenu = addedToAttachmentMenu
    )
}

private fun TdApi.User.resolveAvatarPath(): String? {
    val big = profilePhoto?.big?.local?.path?.takeIf { isValidFilePath(it) }
    val small = profilePhoto?.small?.local?.path?.takeIf { isValidFilePath(it) }
    return big ?: small
}

private fun TdApi.User.getEmojiStatusId(): Long {
    return when (val type = emojiStatus?.type) {
        is TdApi.EmojiStatusTypeCustomEmoji -> type.customEmojiId
        is TdApi.EmojiStatusTypeUpgradedGift -> type.modelCustomEmojiId
        else -> 0L
    }
}

private fun TdApi.Usernames.toDomain(): UsernamesModel {
    return UsernamesModel(
        activeUsernames = activeUsernames.toList(),
        disabledUsernames = disabledUsernames.toList(),
        collectibleUsernames = collectibleUsernames.toList()
    )
}

private fun TdApi.UserType?.toDomain(): UserTypeEnum {
    return when (this) {
        is TdApi.UserTypeRegular -> UserTypeEnum.REGULAR
        is TdApi.UserTypeBot -> UserTypeEnum.BOT
        is TdApi.UserTypeDeleted -> UserTypeEnum.DELETED
        else -> UserTypeEnum.UNKNOWN
    }
}

private fun TdApi.UserStatus?.toDomain(): UserStatusType {
    return when (this) {
        is TdApi.UserStatusOnline -> UserStatusType.ONLINE
        is TdApi.UserStatusRecently -> UserStatusType.RECENTLY
        is TdApi.UserStatusLastWeek -> UserStatusType.LAST_WEEK
        is TdApi.UserStatusLastMonth -> UserStatusType.LAST_MONTH
        else -> UserStatusType.OFFLINE
    }
}

private fun TdApi.UserTypeBot.toDomain(): UserTypeBotInfoModel {
    return UserTypeBotInfoModel(
        canBeEdited = canBeEdited,
        canJoinGroups = canJoinGroups,
        canReadAllGroupMessages = canReadAllGroupMessages,
        hasMainWebApp = hasMainWebApp,
        hasTopics = hasTopics,
        allowsUsersToCreateTopics = allowsUsersToCreateTopics,
        canManageBots = canManageBots,
        isInline = isInline,
        inlineQueryPlaceholder = inlineQueryPlaceholder.ifEmpty { null },
        needLocation = needLocation,
        canConnectToBusiness = canConnectToBusiness,
        canBeAddedToAttachmentMenu = canBeAddedToAttachmentMenu,
        activeUserCount = activeUserCount
    )
}

private fun TdApi.RestrictionInfo.toDomain(): RestrictionInfoModel {
    return RestrictionInfoModel(
        restrictionReason = restrictionReason.ifEmpty { null },
        hasSensitiveContent = hasSensitiveContent
    )
}

private fun TdApi.ActiveStoryState.toDomain(): ActiveStoryStateModel {
    return when (this) {
        is TdApi.ActiveStoryStateLive -> ActiveStoryStateModel(ActiveStoryStateType.LIVE, storyId)
        is TdApi.ActiveStoryStateUnread -> ActiveStoryStateModel(ActiveStoryStateType.UNREAD, 0)
        is TdApi.ActiveStoryStateRead -> ActiveStoryStateModel(ActiveStoryStateType.READ, 0)
        else -> ActiveStoryStateModel(ActiveStoryStateType.UNKNOWN, 0)
    }
}
