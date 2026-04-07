package org.monogram.data.mapper.user

import org.drinkless.tdlib.TdApi
import org.monogram.data.db.model.ChatEntity
import org.monogram.data.db.model.ChatFullInfoEntity
import org.monogram.domain.models.BotVerificationModel
import org.monogram.domain.models.ChatFullInfoModel

fun TdApi.SupergroupFullInfo.toEntity(chatId: Long): ChatFullInfoEntity {
    return ChatFullInfoEntity(
        chatId = chatId,
        description = description.ifEmpty { null },
        inviteLink = inviteLink?.inviteLink,
        memberCount = memberCount,
        onlineCount = 0,
        administratorCount = administratorCount,
        restrictedCount = restrictedCount,
        bannedCount = bannedCount,
        directMessagesChatId = directMessagesChatId,
        commonGroupsCount = 0,
        giftCount = giftCount,
        isBlocked = false,
        botInfo = null,
        botInfoData = null,
        blockListType = null,
        publicPhotoPath = null,
        usesUnofficialApp = false,
        hasSponsoredMessagesEnabled = false,
        needPhoneNumberPrivacyException = false,
        botVerificationBotUserId = botVerification?.botUserId ?: 0L,
        botVerificationIconCustomEmojiId = botVerification?.iconCustomEmojiId ?: 0L,
        botVerificationCustomDescription = botVerification?.customDescription?.text?.ifEmpty { null },
        mainProfileTab = mainProfileTab.toTypeString(),
        firstProfileAudioData = null,
        ratingData = null,
        pendingRatingData = null,
        pendingRatingDate = 0,
        slowModeDelay = slowModeDelay,
        slowModeDelayExpiresIn = slowModeDelayExpiresIn,
        locationAddress = location?.address?.ifEmpty { null },
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
        myBoostCount = myBoostCount,
        unrestrictBoostCount = unrestrictBoostCount,
        stickerSetId = stickerSetId,
        customEmojiStickerSetId = customEmojiStickerSetId,
        botCommandsData = encodeBotCommands(botCommands),
        upgradedFromBasicGroupId = upgradedFromBasicGroupId,
        upgradedFromMaxMessageId = upgradedFromMaxMessageId,
        linkedChatId = linkedChatId,
        note = null,
        canBeCalled = false,
        supportsVideoCalls = false,
        hasPrivateCalls = false,
        hasPrivateForwards = false,
        hasRestrictedVoiceAndVideoNoteMessages = false,
        hasPostedToProfileStories = false,
        setChatBackground = false,
        incomingPaidMessageStarCount = 0,
        outgoingPaidMessageStarCount = outgoingPaidMessageStarCount,
        createdAt = System.currentTimeMillis()
    )
}

fun TdApi.BasicGroupFullInfo.toEntity(chatId: Long): ChatFullInfoEntity {
    return ChatFullInfoEntity(
        chatId = chatId,
        description = description.ifEmpty { null },
        inviteLink = inviteLink?.inviteLink,
        memberCount = members.size,
        onlineCount = 0,
        administratorCount = 0,
        restrictedCount = 0,
        bannedCount = 0,
        commonGroupsCount = 0,
        giftCount = 0,
        isBlocked = false,
        botInfo = null,
        slowModeDelay = 0,
        locationAddress = null,
        canSetStickerSet = false,
        canSetLocation = false,
        canGetMembers = false,
        canGetStatistics = false,
        canGetRevenueStatistics = false,
        linkedChatId = 0,
        note = null,
        canBeCalled = false,
        supportsVideoCalls = false,
        hasPrivateCalls = false,
        hasPrivateForwards = false,
        hasRestrictedVoiceAndVideoNoteMessages = false,
        hasPostedToProfileStories = false,
        setChatBackground = false,
        incomingPaidMessageStarCount = 0,
        outgoingPaidMessageStarCount = 0,
        createdAt = System.currentTimeMillis()
    )
}

fun ChatEntity.toTdApiChat(): TdApi.Chat {
    return TdApi.Chat().apply {
        id = this@toTdApiChat.id
        title = this@toTdApiChat.title
        unreadCount = this@toTdApiChat.unreadCount
        unreadMentionCount = this@toTdApiChat.unreadMentionCount
        unreadReactionCount = this@toTdApiChat.unreadReactionCount
        photo = avatarPath?.let { path ->
            TdApi.ChatPhotoInfo().apply {
                small = TdApi.File().apply { local = TdApi.LocalFile().apply { this.path = path } }
            }
        }
        lastMessage = TdApi.Message().apply {
            content = TdApi.MessageText().apply { text = TdApi.FormattedText(lastMessageText, emptyArray()) }
            date = lastMessageTime.toIntOrNull() ?: 0
            id = this@toTdApiChat.lastMessageId
            isOutgoing = this@toTdApiChat.isLastMessageOutgoing
        }
        positions = arrayOf(TdApi.ChatPosition(TdApi.ChatListMain(), order, isPinned, null))
        notificationSettings = TdApi.ChatNotificationSettings().apply {
            muteFor = if (isMuted) Int.MAX_VALUE else 0
        }
        type = when (this@toTdApiChat.type) {
            "PRIVATE" -> TdApi.ChatTypePrivate().apply {
                userId =
                    if (this@toTdApiChat.privateUserId != 0L) this@toTdApiChat.privateUserId else (this@toTdApiChat.messageSenderId
                        ?: 0L)
            }

            "BASIC_GROUP" -> TdApi.ChatTypeBasicGroup().apply {
                basicGroupId = this@toTdApiChat.basicGroupId
            }

            "SUPERGROUP" -> TdApi.ChatTypeSupergroup(this@toTdApiChat.supergroupId, isChannel)
            "SECRET" -> TdApi.ChatTypeSecret().apply {
                secretChatId = this@toTdApiChat.secretChatId
            }

            else -> TdApi.ChatTypePrivate().apply { userId = this@toTdApiChat.privateUserId }
        }
        isMarkedAsUnread = this@toTdApiChat.isMarkedAsUnread
        hasProtectedContent = this@toTdApiChat.hasProtectedContent
        isTranslatable = this@toTdApiChat.isTranslatable
        viewAsTopics = this@toTdApiChat.viewAsTopics
        accentColorId = this@toTdApiChat.accentColorId
        profileAccentColorId = this@toTdApiChat.profileAccentColorId
        backgroundCustomEmojiId = this@toTdApiChat.backgroundCustomEmojiId
        messageAutoDeleteTime = this@toTdApiChat.messageAutoDeleteTime
        canBeDeletedOnlyForSelf = this@toTdApiChat.canBeDeletedOnlyForSelf
        canBeDeletedForAllUsers = this@toTdApiChat.canBeDeletedForAllUsers
        canBeReported = this@toTdApiChat.canBeReported
        lastReadInboxMessageId = this@toTdApiChat.lastReadInboxMessageId
        lastReadOutboxMessageId = this@toTdApiChat.lastReadOutboxMessageId
        replyMarkupMessageId = this@toTdApiChat.replyMarkupMessageId
        messageSenderId = this@toTdApiChat.messageSenderId?.let { TdApi.MessageSenderUser(it) }
        blockList = if (this@toTdApiChat.blockList) TdApi.BlockListMain() else null
        permissions = TdApi.ChatPermissions(
            this@toTdApiChat.permissionCanSendBasicMessages,
            this@toTdApiChat.permissionCanSendAudios,
            this@toTdApiChat.permissionCanSendDocuments,
            this@toTdApiChat.permissionCanSendPhotos,
            this@toTdApiChat.permissionCanSendVideos,
            this@toTdApiChat.permissionCanSendVideoNotes,
            this@toTdApiChat.permissionCanSendVoiceNotes,
            this@toTdApiChat.permissionCanSendPolls,
            this@toTdApiChat.permissionCanSendOtherMessages,
            this@toTdApiChat.permissionCanAddLinkPreviews,
            this@toTdApiChat.permissionCanEditTag,
            this@toTdApiChat.permissionCanChangeInfo,
            this@toTdApiChat.permissionCanInviteUsers,
            this@toTdApiChat.permissionCanPinMessages,
            this@toTdApiChat.permissionCanCreateTopics
        )
        clientData = "mc:${this@toTdApiChat.memberCount};oc:${this@toTdApiChat.onlineCount}"
    }
}

fun ChatFullInfoEntity.toDomain(): ChatFullInfoModel {
    val botVerificationModel = if (
        botVerificationBotUserId != 0L ||
        botVerificationIconCustomEmojiId != 0L ||
        !botVerificationCustomDescription.isNullOrEmpty()
    ) {
        BotVerificationModel(
            botUserId = botVerificationBotUserId,
            iconCustomEmojiId = botVerificationIconCustomEmojiId,
            customDescription = botVerificationCustomDescription
        )
    } else {
        null
    }

    return ChatFullInfoModel(
        description = description,
        inviteLink = inviteLink,
        memberCount = memberCount,
        onlineCount = onlineCount,
        administratorCount = administratorCount,
        restrictedCount = restrictedCount,
        bannedCount = bannedCount,
        directMessagesChatId = directMessagesChatId,
        commonGroupsCount = commonGroupsCount,
        giftCount = giftCount,
        isBlocked = isBlocked,
        botInfo = botInfo,
        botInfoModel = null,
        blockListType = blockListType,
        canGetRevenueStatistics = canGetRevenueStatistics,
        canGetStarRevenueStatistics = canGetStarRevenueStatistics,
        canEnablePaidMessages = canEnablePaidMessages,
        canEnablePaidReaction = canEnablePaidReaction,
        hasHiddenMembers = hasHiddenMembers,
        canHideMembers = canHideMembers,
        canToggleAggressiveAntiSpam = canToggleAggressiveAntiSpam,
        isAllHistoryAvailable = isAllHistoryAvailable,
        canHaveSponsoredMessages = canHaveSponsoredMessages,
        hasAggressiveAntiSpamEnabled = hasAggressiveAntiSpamEnabled,
        hasPaidMediaAllowed = hasPaidMediaAllowed,
        hasPinnedStories = hasPinnedStories,
        linkedChatId = linkedChatId,
        businessInfo = null,
        publicPhotoPath = publicPhotoPath,
        note = note,
        usesUnofficialApp = usesUnofficialApp,
        hasSponsoredMessagesEnabled = hasSponsoredMessagesEnabled,
        needPhoneNumberPrivacyException = needPhoneNumberPrivacyException,
        botVerification = botVerificationModel,
        mainProfileTab = mainProfileTab.toProfileTabType(),
        firstProfileAudio = decodeProfileAudio(firstProfileAudioData),
        rating = decodeUserRating(ratingData),
        pendingRating = decodeUserRating(pendingRatingData),
        pendingRatingDate = pendingRatingDate,
        canBeCalled = canBeCalled,
        supportsVideoCalls = supportsVideoCalls,
        hasPrivateCalls = hasPrivateCalls,
        hasPrivateForwards = hasPrivateForwards,
        hasRestrictedVoiceAndVideoNoteMessages = hasRestrictedVoiceAndVideoNoteMessages,
        hasPostedToProfileStories = hasPostedToProfileStories,
        setChatBackground = setChatBackground,
        slowModeDelay = slowModeDelay,
        slowModeDelayExpiresIn = slowModeDelayExpiresIn,
        locationAddress = locationAddress,
        canSetStickerSet = canSetStickerSet,
        canSetLocation = canSetLocation,
        canGetMembers = canGetMembers,
        canGetStatistics = canGetStatistics,
        myBoostCount = myBoostCount,
        unrestrictBoostCount = unrestrictBoostCount,
        stickerSetId = stickerSetId,
        customEmojiStickerSetId = customEmojiStickerSetId,
        botCommands = decodeBotCommands(botCommandsData),
        upgradedFromBasicGroupId = upgradedFromBasicGroupId,
        upgradedFromMaxMessageId = upgradedFromMaxMessageId,
        incomingPaidMessageStarCount = incomingPaidMessageStarCount,
        outgoingPaidMessageStarCount = outgoingPaidMessageStarCount
    )
}
