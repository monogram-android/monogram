package org.monogram.data.mapper.user

import org.drinkless.tdlib.TdApi
import org.monogram.data.mapper.isChannelType
import org.monogram.data.mapper.isGroupType
import org.monogram.data.mapper.isValidFilePath
import org.monogram.data.mapper.toDomainChatType
import org.monogram.domain.models.*

fun TdApi.UserFullInfo.mapUserFullInfoToChat(): ChatFullInfoModel {
    val birthdate = birthdate?.let { date ->
        BirthdateModel(date.day, date.month, if (date.year > 0) date.year else null)
    }
    return ChatFullInfoModel(
        description = bio?.text?.ifEmpty { null },
        commonGroupsCount = groupInCommonCount,
        giftCount = giftCount,
        birthdate = birthdate,
        isBlocked = blockList != null,
        blockListType = blockList.toTypeString(),
        botInfo = botInfo?.description?.ifEmpty { null },
        botInfoModel = botInfo?.toDomain(),
        canGetRevenueStatistics = botInfo?.canGetRevenueStatistics ?: false,
        linkedChatId = personalChatId,
        businessInfo = businessInfo?.toDomain(),
        publicPhotoPath = publicPhoto.resolveChatPhotoPath(),
        usesUnofficialApp = usesUnofficialApp,
        hasSponsoredMessagesEnabled = hasSponsoredMessagesEnabled,
        needPhoneNumberPrivacyException = needPhoneNumberPrivacyException,
        botVerification = botVerification?.toDomain(),
        mainProfileTab = mainProfileTab?.toDomain(),
        firstProfileAudio = firstProfileAudio?.toDomain(),
        rating = rating?.toDomain(),
        pendingRating = pendingRating?.toDomain(),
        pendingRatingDate = pendingRatingDate,
        note = note?.text?.ifEmpty { null },
        canBeCalled = canBeCalled,
        supportsVideoCalls = supportsVideoCalls,
        hasPrivateCalls = hasPrivateCalls,
        hasPrivateForwards = hasPrivateForwards,
        hasRestrictedVoiceAndVideoNoteMessages = hasRestrictedVoiceAndVideoNoteMessages,
        hasPostedToProfileStories = hasPostedToProfileStories,
        setChatBackground = setChatBackground,
        incomingPaidMessageStarCount = incomingPaidMessageStarCount,
        outgoingPaidMessageStarCount = outgoingPaidMessageStarCount
    )
}

fun TdApi.SupergroupFullInfo.mapSupergroupFullInfoToChat(
    supergroup: TdApi.Supergroup?
): ChatFullInfoModel {
    val link = inviteLink?.inviteLink
        ?: supergroup?.usernames?.activeUsernames?.firstOrNull()?.let { "t.me/$it" }
    return ChatFullInfoModel(
        description = description.ifEmpty { null },
        inviteLink = link,
        memberCount = memberCount,
        administratorCount = administratorCount,
        restrictedCount = restrictedCount,
        bannedCount = bannedCount,
        directMessagesChatId = directMessagesChatId,
        slowModeDelay = slowModeDelay,
        slowModeDelayExpiresIn = slowModeDelayExpiresIn,
        locationAddress = location?.address?.ifEmpty { null },
        giftCount = giftCount,
        canEnablePaidMessages = canEnablePaidMessages,
        canEnablePaidReaction = canEnablePaidReaction,
        hasHiddenMembers = hasHiddenMembers,
        canHideMembers = canHideMembers,
        canSetStickerSet = canSetStickerSet,
        canSetLocation = canSetLocation,
        canGetMembers = canGetMembers,
        canGetStatistics = canGetStatistics,
        canGetRevenueStatistics = canGetRevenueStatistics,
        canGetStarRevenueStatistics = canGetStarRevenueStatistics,
        canToggleAggressiveAntiSpam = canToggleAggressiveAntiSpam,
        isAllHistoryAvailable = isAllHistoryAvailable,
        canHaveSponsoredMessages = canHaveSponsoredMessages,
        hasAggressiveAntiSpamEnabled = hasAggressiveAntiSpamEnabled,
        hasPaidMediaAllowed = hasPaidMediaAllowed,
        hasPinnedStories = hasPinnedStories,
        linkedChatId = linkedChatId,
        botVerification = botVerification?.toDomain(),
        mainProfileTab = mainProfileTab?.toDomain(),
        myBoostCount = myBoostCount,
        unrestrictBoostCount = unrestrictBoostCount,
        stickerSetId = stickerSetId,
        customEmojiStickerSetId = customEmojiStickerSetId,
        botCommands = botCommands?.map { it.toDomain() } ?: emptyList(),
        upgradedFromBasicGroupId = upgradedFromBasicGroupId,
        upgradedFromMaxMessageId = upgradedFromMaxMessageId,
        outgoingPaidMessageStarCount = outgoingPaidMessageStarCount
    )
}

fun TdApi.BasicGroupFullInfo.mapBasicGroupFullInfoToChat(): ChatFullInfoModel {
    return ChatFullInfoModel(
        description = description.ifEmpty { null },
        inviteLink = inviteLink?.inviteLink,
        memberCount = members.size
    )
}

fun TdApi.Chat.toDomain(): ChatModel {
    val isChannel = type.isChannelType()
    return ChatModel(
        id = id,
        title = title,
        avatarPath = photo?.small?.local?.path?.takeIf { isValidFilePath(it) },
        unreadCount = unreadCount,
        isMuted = notificationSettings.muteFor > 0,
        isChannel = isChannel,
        isGroup = type.isGroupType(),
        type = type.toDomainChatType(),
        lastMessageText = (lastMessage?.content as? TdApi.MessageText)?.text?.text ?: ""
    )
}

private fun TdApi.BotVerification.toDomain(): BotVerificationModel {
    return BotVerificationModel(
        botUserId = botUserId,
        iconCustomEmojiId = iconCustomEmojiId,
        customDescription = customDescription.text.ifEmpty { null }
    )
}

private fun TdApi.BotVerificationParameters.toDomain(): BotVerificationParametersModel {
    return BotVerificationParametersModel(
        iconCustomEmojiId = iconCustomEmojiId,
        organizationName = organizationName.ifEmpty { null },
        defaultCustomDescription = defaultCustomDescription?.text?.ifEmpty { null },
        canSetCustomDescription = canSetCustomDescription
    )
}

private fun TdApi.UserRating.toDomain(): UserRatingModel {
    return UserRatingModel(
        level = level,
        isMaximumLevelReached = isMaximumLevelReached,
        rating = rating,
        currentLevelRating = currentLevelRating,
        nextLevelRating = nextLevelRating
    )
}

private fun TdApi.Audio.toDomain(): ProfileAudioModel {
    val filePath = audio.local.path.takeIf { isValidFilePath(it) }
    return ProfileAudioModel(
        duration = duration,
        title = title.ifEmpty { null },
        performer = performer.ifEmpty { null },
        fileName = fileName.ifEmpty { null },
        mimeType = mimeType.ifEmpty { null },
        fileId = audio.id,
        filePath = filePath
    )
}

private fun TdApi.ProfileTab.toDomain(): ProfileTabType {
    return when (this) {
        is TdApi.ProfileTabPosts -> ProfileTabType.POSTS
        is TdApi.ProfileTabGifts -> ProfileTabType.GIFTS
        is TdApi.ProfileTabMedia -> ProfileTabType.MEDIA
        is TdApi.ProfileTabFiles -> ProfileTabType.FILES
        is TdApi.ProfileTabLinks -> ProfileTabType.LINKS
        is TdApi.ProfileTabMusic -> ProfileTabType.MUSIC
        is TdApi.ProfileTabVoice -> ProfileTabType.VOICE
        is TdApi.ProfileTabGifs -> ProfileTabType.GIFS
        else -> ProfileTabType.UNKNOWN
    }
}

private fun TdApi.BotCommand.toDomain(): BotCommandModel {
    return BotCommandModel(
        command = command,
        description = description
    )
}

private fun TdApi.BotCommands.toDomain(): SupergroupBotCommandsModel {
    return SupergroupBotCommandsModel(
        botUserId = botUserId,
        commands = commands?.map { it.toDomain() } ?: emptyList()
    )
}

private fun TdApi.ChatAdministratorRights.toDomain(): ChatAdministratorRightsModel {
    return ChatAdministratorRightsModel(
        canManageChat = canManageChat,
        canChangeInfo = canChangeInfo,
        canPostMessages = canPostMessages,
        canEditMessages = canEditMessages,
        canDeleteMessages = canDeleteMessages,
        canInviteUsers = canInviteUsers,
        canRestrictMembers = canRestrictMembers,
        canPinMessages = canPinMessages,
        canManageTopics = canManageTopics,
        canPromoteMembers = canPromoteMembers,
        canManageVideoChats = canManageVideoChats,
        canPostStories = canPostStories,
        canEditStories = canEditStories,
        canDeleteStories = canDeleteStories,
        canManageDirectMessages = canManageDirectMessages,
        canManageTags = canManageTags,
        isAnonymous = isAnonymous
    )
}

private fun TdApi.AffiliateProgramInfo.toDomain(): AffiliateProgramInfoModel {
    return AffiliateProgramInfoModel(
        commissionPerMille = parameters.commissionPerMille,
        monthCount = parameters.monthCount,
        endDate = endDate,
        dailyRevenuePerUserStarCount = dailyRevenuePerUserAmount.starCount,
        dailyRevenuePerUserNanostarCount = dailyRevenuePerUserAmount.nanostarCount
    )
}

private fun TdApi.BotInfo.toDomain(): BotInfoModel {
    return BotInfoModel(
        commands = commands?.map { it.toDomain() } ?: emptyList(),
        menuButton = when (val button = menuButton) {
            is TdApi.BotMenuButton -> BotMenuButtonModel.WebApp(button.text, button.url)
            null -> BotMenuButtonModel.Commands
            else -> BotMenuButtonModel.Default
        },
        shortDescription = shortDescription.ifEmpty { null },
        description = description.ifEmpty { null },
        photoFileId = photo?.sizes?.lastOrNull()?.photo?.id ?: 0,
        photoPath = photo?.resolvePhotoPath(),
        animationFileId = animation?.animation?.id ?: 0,
        animationPath = animation?.animation?.local?.path?.takeIf { isValidFilePath(it) },
        managerBotUserId = managerBotUserId,
        privacyPolicyUrl = privacyPolicyUrl.ifEmpty { null },
        defaultGroupAdministratorRights = defaultGroupAdministratorRights?.toDomain(),
        defaultChannelAdministratorRights = defaultChannelAdministratorRights?.toDomain(),
        affiliateProgram = affiliateProgram?.toDomain(),
        webAppBackgroundLightColor = webAppBackgroundLightColor,
        webAppBackgroundDarkColor = webAppBackgroundDarkColor,
        webAppHeaderLightColor = webAppHeaderLightColor,
        webAppHeaderDarkColor = webAppHeaderDarkColor,
        verificationParameters = verificationParameters?.toDomain(),
        canGetRevenueStatistics = canGetRevenueStatistics,
        canManageEmojiStatus = canManageEmojiStatus,
        hasMediaPreviews = hasMediaPreviews,
        editCommandsLinkType = editCommandsLink?.javaClass?.simpleName,
        editDescriptionLinkType = editDescriptionLink?.javaClass?.simpleName,
        editDescriptionMediaLinkType = editDescriptionMediaLink?.javaClass?.simpleName,
        editSettingsLinkType = editSettingsLink?.javaClass?.simpleName
    )
}

private fun TdApi.Photo.resolvePhotoPath(): String? {
    val bestPhotoSize = sizes.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: sizes.lastOrNull()
    return bestPhotoSize?.photo?.local?.path?.takeIf { isValidFilePath(it) }
}

private fun TdApi.ChatPhoto?.resolveChatPhotoPath(): String? {
    if (this == null) return null
    val bestPhotoSize = sizes.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: sizes.lastOrNull()
    return animation?.file?.local?.path?.takeIf { isValidFilePath(it) }
        ?: bestPhotoSize?.photo?.local?.path?.takeIf { isValidFilePath(it) }
}

private fun TdApi.BusinessInfo.toDomain(): BusinessInfoModel {
    return BusinessInfoModel(
        location = location?.let {
            BusinessLocationModel(
                it.location!!.latitude,
                it.location!!.longitude,
                it.address
            )
        },
        openingHours = openingHours?.let {
            BusinessOpeningHoursModel(
                it.timeZoneId,
                it.openingHours.map { interval ->
                    BusinessOpeningHoursIntervalModel(interval.startMinute, interval.endMinute)
                }
            )
        },
        startPage = startPage?.let {
            BusinessStartPageModel(
                title = it.title,
                message = it.message,
                stickerPath = it.sticker?.sticker?.local?.path?.takeIf { path -> isValidFilePath(path) }
            )
        },
        nextOpenIn = nextOpenIn,
        nextCloseIn = nextCloseIn
    )
}
