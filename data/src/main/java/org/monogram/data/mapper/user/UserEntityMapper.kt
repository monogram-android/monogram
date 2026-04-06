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
        isSupport = isSupport,
        isContact = isContact,
        isMutualContact = isMutualContact,
        isCloseFriend = isCloseFriend,
        haveAccess = haveAccess,
        username = usernames?.activeUsernames?.firstOrNull(),
        usernamesData = usernamesData,
        statusType = statusType,
        accentColorId = accentColorId,
        profileAccentColorId = profileAccentColorId,
        statusEmojiId = statusEmojiId,
        languageCode = languageCode.ifEmpty { null },
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