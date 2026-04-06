package org.monogram.data.mapper.user

import org.drinkless.tdlib.TdApi
import org.monogram.data.db.model.UserEntity

fun TdApi.User.toEntity(personalAvatarPath: String?): UserEntity {
    val usernamesData = buildString {
        append(usernames?.activeUsernames?.joinToString("|").orEmpty())
        append('\n')
        append(usernames?.disabledUsernames?.joinToString("|").orEmpty())
        append('\n')
        append(usernames?.editableUsername.orEmpty())
        append('\n')
        append(usernames?.collectibleUsernames?.joinToString("|").orEmpty())
    }

    val statusType = when (status) {
        is TdApi.UserStatusOnline -> "ONLINE"
        is TdApi.UserStatusRecently -> "RECENTLY"
        is TdApi.UserStatusLastWeek -> "LAST_WEEK"
        is TdApi.UserStatusLastMonth -> "LAST_MONTH"
        else -> "OFFLINE"
    }

    val statusEmojiId = when (val type = emojiStatus?.type) {
        is TdApi.EmojiStatusTypeCustomEmoji -> type.customEmojiId
        is TdApi.EmojiStatusTypeUpgradedGift -> type.modelCustomEmojiId
        else -> 0L
    }

    val botType = type as? TdApi.UserTypeBot

    return UserEntity(
        id = id,
        firstName = firstName,
        lastName = lastName.ifEmpty { null },
        phoneNumber = phoneNumber.ifEmpty { null },
        avatarPath = profilePhoto?.big?.local?.path?.ifEmpty { null }
            ?: profilePhoto?.small?.local?.path?.ifEmpty { null },
        personalAvatarPath = personalAvatarPath,
        isPremium = isPremium,
        isVerified = verificationStatus?.isVerified ?: false,
        isScam = verificationStatus?.isScam ?: false,
        isFake = verificationStatus?.isFake ?: false,
        botVerificationIconCustomEmojiId = verificationStatus?.botVerificationIconCustomEmojiId ?: 0L,
        isSupport = isSupport,
        isContact = isContact,
        isMutualContact = isMutualContact,
        isCloseFriend = isCloseFriend,
        botTypeCanBeEdited = botType?.canBeEdited ?: false,
        botTypeCanJoinGroups = botType?.canJoinGroups ?: false,
        botTypeCanReadAllGroupMessages = botType?.canReadAllGroupMessages ?: false,
        botTypeHasMainWebApp = botType?.hasMainWebApp ?: false,
        botTypeHasTopics = botType?.hasTopics ?: false,
        botTypeAllowsUsersToCreateTopics = botType?.allowsUsersToCreateTopics ?: false,
        botTypeCanManageBots = botType?.canManageBots ?: false,
        botTypeIsInline = botType?.isInline ?: false,
        botTypeInlineQueryPlaceholder = botType?.inlineQueryPlaceholder?.ifEmpty { null },
        botTypeNeedLocation = botType?.needLocation ?: false,
        botTypeCanConnectToBusiness = botType?.canConnectToBusiness ?: false,
        botTypeCanBeAddedToAttachmentMenu = botType?.canBeAddedToAttachmentMenu ?: false,
        botTypeActiveUserCount = botType?.activeUserCount ?: 0,
        userType = type.toTypeString(),
        restrictionReason = restrictionInfo?.restrictionReason?.ifEmpty { null },
        hasSensitiveContent = restrictionInfo?.hasSensitiveContent ?: false,
        activeStoryStateType = activeStoryState.toTypeString(),
        activeStoryId = (activeStoryState as? TdApi.ActiveStoryStateLive)?.storyId ?: 0,
        restrictsNewChats = restrictsNewChats,
        paidMessageStarCount = paidMessageStarCount,
        haveAccess = haveAccess,
        username = usernames?.activeUsernames?.firstOrNull(),
        usernamesData = usernamesData,
        statusType = statusType,
        accentColorId = accentColorId,
        backgroundCustomEmojiId = backgroundCustomEmojiId,
        profileAccentColorId = profileAccentColorId,
        profileBackgroundCustomEmojiId = profileBackgroundCustomEmojiId,
        statusEmojiId = statusEmojiId,
        languageCode = languageCode.ifEmpty { null },
        addedToAttachmentMenu = addedToAttachmentMenu,
        lastSeen = (status as? TdApi.UserStatusOffline)?.wasOnline?.toLong() ?: 0L,
        createdAt = System.currentTimeMillis()
    )
}

fun TdApi.UserFullInfo.extractPersonalAvatarPath(): String? {
    val bestPhotoSize = personalPhoto?.sizes?.maxByOrNull { it.width.toLong() * it.height.toLong() }
        ?: personalPhoto?.sizes?.lastOrNull()
    return personalPhoto?.animation?.file?.local?.path?.ifEmpty { null }
        ?: bestPhotoSize?.photo?.local?.path?.ifEmpty { null }
}

fun UserEntity.toTdApi(): TdApi.User {
    return TdApi.User().apply {
        id = this@toTdApi.id
        firstName = this@toTdApi.firstName
        lastName = this@toTdApi.lastName ?: ""
        phoneNumber = this@toTdApi.phoneNumber ?: ""
        isPremium = this@toTdApi.isPremium
        isSupport = this@toTdApi.isSupport
        isContact = this@toTdApi.isContact
        isMutualContact = this@toTdApi.isMutualContact
        isCloseFriend = this@toTdApi.isCloseFriend
        haveAccess = this@toTdApi.haveAccess
        languageCode = this@toTdApi.languageCode ?: ""
        accentColorId = this@toTdApi.accentColorId
        backgroundCustomEmojiId = this@toTdApi.backgroundCustomEmojiId
        profileAccentColorId = this@toTdApi.profileAccentColorId
        profileBackgroundCustomEmojiId = this@toTdApi.profileBackgroundCustomEmojiId
        verificationStatus = if (
            isVerified ||
            isScam ||
            isFake ||
            botVerificationIconCustomEmojiId != 0L
        ) {
            TdApi.VerificationStatus(
                isVerified,
                isScam,
                isFake,
                botVerificationIconCustomEmojiId
            )
        } else {
            null
        }
        val (active, disabled, editable, collectible) = decodeUsernames(
            this@toTdApi.usernamesData,
            this@toTdApi.username
        )
        usernames = TdApi.Usernames(active, disabled, editable, collectible)
        emojiStatus = this@toTdApi.statusEmojiId.takeIf { it != 0L }?.let {
            TdApi.EmojiStatus(TdApi.EmojiStatusTypeCustomEmoji(it), 0)
        }
        status = when (this@toTdApi.statusType) {
            "ONLINE" -> TdApi.UserStatusOnline(0)
            "RECENTLY" -> TdApi.UserStatusRecently()
            "LAST_WEEK" -> TdApi.UserStatusLastWeek()
            "LAST_MONTH" -> TdApi.UserStatusLastMonth()
            else -> TdApi.UserStatusOffline(lastSeen.toInt())
        }
        type = when (this@toTdApi.userType) {
            "REGULAR" -> TdApi.UserTypeRegular()
            "BOT" -> TdApi.UserTypeBot(
                botTypeCanBeEdited,
                botTypeCanJoinGroups,
                botTypeCanReadAllGroupMessages,
                botTypeHasMainWebApp,
                botTypeHasTopics,
                botTypeAllowsUsersToCreateTopics,
                botTypeCanManageBots,
                botTypeIsInline,
                botTypeInlineQueryPlaceholder.orEmpty(),
                botTypeNeedLocation,
                botTypeCanConnectToBusiness,
                botTypeCanBeAddedToAttachmentMenu,
                botTypeActiveUserCount
            )

            "DELETED" -> TdApi.UserTypeDeleted()
            else -> TdApi.UserTypeUnknown()
        }
        restrictionInfo = if (!restrictionReason.isNullOrBlank() || hasSensitiveContent) {
            TdApi.RestrictionInfo(
                restrictionReason.orEmpty(),
                hasSensitiveContent
            )
        } else {
            null
        }
        activeStoryState = when (activeStoryStateType) {
            "LIVE" -> TdApi.ActiveStoryStateLive(activeStoryId)
            "UNREAD" -> TdApi.ActiveStoryStateUnread()
            "READ" -> TdApi.ActiveStoryStateRead()
            else -> null
        }
        restrictsNewChats = this@toTdApi.restrictsNewChats
        paidMessageStarCount = this@toTdApi.paidMessageStarCount
        addedToAttachmentMenu = this@toTdApi.addedToAttachmentMenu
        profilePhoto = avatarPath?.let { path ->
            TdApi.ProfilePhoto().apply {
                small = TdApi.File().apply { local = TdApi.LocalFile().apply { this.path = path } }
            }
        }
    }
}

private fun decodeUsernames(data: String?, fallbackUsername: String?): QuadUsernames {
    if (data.isNullOrEmpty()) {
        val active = fallbackUsername?.takeIf { it.isNotBlank() }?.let { arrayOf(it) } ?: emptyArray()
        return QuadUsernames(active, emptyArray(), fallbackUsername.orEmpty(), emptyArray())
    }
    val parts = data.split("\n", limit = 4)
    val active = parts.getOrNull(0).orEmpty().split('|').filter { it.isNotBlank() }.toTypedArray()
    val disabled = parts.getOrNull(1).orEmpty().split('|').filter { it.isNotBlank() }.toTypedArray()
    val editable = parts.getOrNull(2).orEmpty()
    val collectible = parts.getOrNull(3).orEmpty().split('|').filter { it.isNotBlank() }.toTypedArray()
    return QuadUsernames(active, disabled, editable, collectible)
}

private data class QuadUsernames(
    val active: Array<String>,
    val disabled: Array<String>,
    val editable: String,
    val collectible: Array<String>
)

private fun TdApi.ActiveStoryState?.toTypeString(): String? {
    return when (this) {
        is TdApi.ActiveStoryStateLive -> "LIVE"
        is TdApi.ActiveStoryStateUnread -> "UNREAD"
        is TdApi.ActiveStoryStateRead -> "READ"
        else -> null
    }
}

private fun TdApi.UserType?.toTypeString(): String {
    return when (this) {
        is TdApi.UserTypeRegular -> "REGULAR"
        is TdApi.UserTypeBot -> "BOT"
        is TdApi.UserTypeDeleted -> "DELETED"
        else -> "UNKNOWN"
    }
}
