package org.monogram.data.mapper.user

import org.drinkless.tdlib.TdApi
import org.monogram.data.db.model.ChatEntity
import org.monogram.data.db.model.ChatFullInfoEntity
import org.monogram.data.db.model.UserEntity
import org.monogram.data.db.model.UserFullInfoEntity
import org.monogram.domain.models.*
import org.monogram.domain.repository.ChatMemberStatus
import org.monogram.domain.repository.ChatMembersFilter

fun TdApi.User.toDomain(
    fullInfo: TdApi.UserFullInfo? = null,
    customEmojiPath: String? = null
): UserModel {
    val emojiStatusId = this.getEmojiStatusId()
    val username = usernames?.activeUsernames?.firstOrNull()

    val personalAvatarPath = fullInfo
        ?.personalPhoto?.sizes?.lastOrNull()?.photo
        ?.local?.path?.ifEmpty { null }

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
        isVerified = verificationStatus?.isVerified ?: false,
        isSupport = isSupport,
        type = type.toDomain(),
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
        lastSeen = lastSeen
    )
}

fun TdApi.ChatMember.toDomain(user: UserModel): GroupMemberModel {
    val rank = when (this.status) {
        is TdApi.ChatMemberStatusCreator -> "Owner"
        is TdApi.ChatMemberStatusAdministrator -> "Admin"
        else -> null
    }
    return GroupMemberModel(
        user = user,
        rank = rank,
        status = this.status.toDomain()
    )
}

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
        botInfo = botInfo?.description?.ifEmpty { null },
        canGetRevenueStatistics = botInfo?.canGetRevenueStatistics ?: false,
        linkedChatId = personalChatId,
        businessInfo = businessInfo?.let { businessInfo!!.toDomain() },
        canBeCalled = canBeCalled,
        supportsVideoCalls = supportsVideoCalls,
        hasPrivateCalls = hasPrivateCalls,
        hasPrivateForwards = hasPrivateForwards,
        hasRestrictedVoiceAndVideoNoteMessages = hasRestrictedVoiceAndVideoNoteMessages,
        hasPostedToProfileStories = hasPostedToProfileStories,
        setChatBackground = setChatBackground
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
        slowModeDelay = slowModeDelay,
        locationAddress = location?.address?.ifEmpty { null },
        giftCount = giftCount,
        canSetStickerSet = canSetStickerSet,
        canSetLocation = canSetLocation,
        canGetMembers = canGetMembers,
        canGetStatistics = canGetStatistics,
        canGetRevenueStatistics = canGetRevenueStatistics,
        linkedChatId = linkedChatId
    )
}

fun TdApi.BasicGroupFullInfo.mapBasicGroupFullInfoToChat(): ChatFullInfoModel {
    return ChatFullInfoModel(
        description = description.ifEmpty { null },
        inviteLink = inviteLink?.inviteLink,
        memberCount = members.size
    )
}

fun TdApi.ChatMemberStatus.toDomain(): ChatMemberStatus {
    return when (this) {
        is TdApi.ChatMemberStatusCreator -> ChatMemberStatus.Creator
        is TdApi.ChatMemberStatusAdministrator -> ChatMemberStatus.Administrator(
            customTitle = "",
            canBeEdited = canBeEdited,
            canManageChat = rights.canManageChat,
            canChangeInfo = rights.canChangeInfo,
            canPostMessages = rights.canPostMessages,
            canEditMessages = rights.canEditMessages,
            canDeleteMessages = rights.canDeleteMessages,
            canInviteUsers = rights.canInviteUsers,
            canRestrictMembers = rights.canRestrictMembers,
            canPinMessages = rights.canPinMessages,
            canManageTopics = rights.canManageTopics,
            canPromoteMembers = rights.canPromoteMembers,
            canManageVideoChats = rights.canManageVideoChats,
            canPostStories = rights.canPostStories,
            canEditStories = rights.canEditStories,
            canDeleteStories = rights.canDeleteStories,
            canManageDirectMessages = rights.canManageDirectMessages,
            isAnonymous = rights.isAnonymous
        )
        is TdApi.ChatMemberStatusRestricted -> ChatMemberStatus.Restricted(
            isMember = isMember,
            restrictedUntilDate = restrictedUntilDate,
            permissions = permissions.toDomain()
        )
        is TdApi.ChatMemberStatusBanned -> ChatMemberStatus.Banned(bannedUntilDate)
        is TdApi.ChatMemberStatusLeft -> ChatMemberStatus.Left
        else -> ChatMemberStatus.Member
    }
}

fun TdApi.Chat.toDomain(): ChatModel {
    val isChannel = type is TdApi.ChatTypeSupergroup &&
            (type as TdApi.ChatTypeSupergroup).isChannel
    return ChatModel(
        id = id,
        title = title,
        avatarPath = photo?.small?.local?.path?.ifEmpty { null },
        unreadCount = unreadCount,
        isMuted = notificationSettings.muteFor > 0,
        isChannel = isChannel,
        isGroup = type is TdApi.ChatTypeBasicGroup ||
                (type is TdApi.ChatTypeSupergroup && !isChannel),
        type = type.toDomain(),
        lastMessageText = (lastMessage?.content as? TdApi.MessageText)?.text?.text ?: ""
    )
}

fun ChatMembersFilter.toApi(): TdApi.SupergroupMembersFilter {
    return when (this) {
        is ChatMembersFilter.Recent -> TdApi.SupergroupMembersFilterRecent()
        is ChatMembersFilter.Administrators -> TdApi.SupergroupMembersFilterAdministrators()
        is ChatMembersFilter.Banned -> TdApi.SupergroupMembersFilterBanned()
        is ChatMembersFilter.Restricted -> TdApi.SupergroupMembersFilterRestricted()
        is ChatMembersFilter.Bots -> TdApi.SupergroupMembersFilterBots()
        is ChatMembersFilter.Search -> TdApi.SupergroupMembersFilterSearch(this.query)
    }
}

fun ChatMemberStatus.toApi(): TdApi.ChatMemberStatus {
    return when (this) {
        is ChatMemberStatus.Member -> TdApi.ChatMemberStatusMember()
        is ChatMemberStatus.Administrator -> TdApi.ChatMemberStatusAdministrator(
            canBeEdited,
            TdApi.ChatAdministratorRights(
                canManageChat,
                canChangeInfo,
                canPostMessages,
                canEditMessages,
                canDeleteMessages,
                canInviteUsers,
                canRestrictMembers,
                canPinMessages,
                canManageTopics,
                canPromoteMembers,
                canManageVideoChats,
                canPostStories,
                canEditStories,
                canDeleteStories,
                canManageDirectMessages,
                false,
                isAnonymous
            )
        )
        is ChatMemberStatus.Restricted -> TdApi.ChatMemberStatusRestricted(
            isMember,
            restrictedUntilDate,
            permissions.toApi()
        )
        is ChatMemberStatus.Left -> TdApi.ChatMemberStatusLeft()
        is ChatMemberStatus.Banned -> TdApi.ChatMemberStatusBanned(bannedUntilDate)
        is ChatMemberStatus.Creator -> TdApi.ChatMemberStatusCreator(false, true)
    }
}

private fun TdApi.ChatType.toDomain(): ChatType = when (this) {
    is TdApi.ChatTypePrivate -> ChatType.PRIVATE
    is TdApi.ChatTypeBasicGroup -> ChatType.BASIC_GROUP
    is TdApi.ChatTypeSupergroup -> ChatType.SUPERGROUP
    is TdApi.ChatTypeSecret -> ChatType.SECRET
    else -> ChatType.PRIVATE
}

private fun TdApi.User.resolveAvatarPath(): String? {
    val big = profilePhoto?.big?.local?.path?.ifEmpty { null }
    val small = profilePhoto?.small?.local?.path?.ifEmpty { null }
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
            BusinessStartPageModel(it.title, it.message, it.sticker?.sticker?.local?.path)
        },
        nextOpenIn = nextOpenIn,
        nextCloseIn = nextCloseIn
    )
}

private fun TdApi.ChatPermissions.toDomain(): ChatPermissionsModel {
    return ChatPermissionsModel(
        canSendBasicMessages = canSendBasicMessages,
        canSendAudios = canSendAudios,
        canSendDocuments = canSendDocuments,
        canSendPhotos = canSendPhotos,
        canSendVideos = canSendVideos,
        canSendVideoNotes = canSendVideoNotes,
        canSendVoiceNotes = canSendVoiceNotes,
        canSendPolls = canSendPolls,
        canSendOtherMessages = canSendOtherMessages,
       canAddLinkPreviews = canAddLinkPreviews,
        canChangeInfo = canChangeInfo,
        canInviteUsers = canInviteUsers,
        canPinMessages = canPinMessages,
       canCreateTopics = canCreateTopics
    )
}

private fun ChatPermissionsModel.toApi(): TdApi.ChatPermissions {
    return TdApi.ChatPermissions(
        canSendBasicMessages,
        canSendAudios,
        canSendDocuments,
        canSendPhotos,
        canSendVideos,
        canSendVideoNotes,
        canSendVoiceNotes,
        canSendPolls,
        canSendOtherMessages,
        canAddLinkPreviews,
        canEditTag,
        canChangeInfo,
        canInviteUsers,
        canPinMessages,
        canCreateTopics
    )
}

fun TdApi.UserFullInfo.toEntity(userId: Long): UserFullInfoEntity {
    return UserFullInfoEntity(
        userId = userId,
        bio = bio?.text?.ifEmpty { null },
        commonGroupsCount = groupInCommonCount,
        giftCount = giftCount,
        isBlocked = blockList != null,
        canBeCalled = canBeCalled,
        supportsVideoCalls = supportsVideoCalls,
        hasPrivateCalls = hasPrivateCalls,
        hasPrivateForwards = hasPrivateForwards,
        hasRestrictedVoiceAndVideoNoteMessages = hasRestrictedVoiceAndVideoNoteMessages,
        hasPostedToProfileStories = hasPostedToProfileStories,
        setChatBackground = setChatBackground,
        canGetRevenueStatistics = botInfo?.canGetRevenueStatistics ?: false,
        createdAt = System.currentTimeMillis()
    )
}

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
        commonGroupsCount = 0,
        giftCount = giftCount,
        isBlocked = false,
        botInfo = null,
        slowModeDelay = slowModeDelay,
        locationAddress = location?.address?.ifEmpty { null },
        canSetStickerSet = canSetStickerSet,
        canSetLocation = canSetLocation,
        canGetMembers = canGetMembers,
        canGetStatistics = canGetStatistics,
        canGetRevenueStatistics = canGetRevenueStatistics,
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
        outgoingPaidMessageStarCount = 0,
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

fun UserEntity.toTdApi(): TdApi.User {
    return TdApi.User().apply {
        id = this@toTdApi.id
        firstName = this@toTdApi.firstName
        lastName = this@toTdApi.lastName ?: ""
        phoneNumber = this@toTdApi.phoneNumber ?: ""
        isPremium = this@toTdApi.isPremium
        verificationStatus = if (isVerified) TdApi.VerificationStatus(true, false, false, 0L) else null
        usernames =
            this@toTdApi.username?.let { TdApi.Usernames(arrayOf(it), emptyArray<String>(), it, emptyArray<String>()) }
        status = TdApi.UserStatusOffline(lastSeen.toInt())
        profilePhoto = avatarPath?.let { path ->
            TdApi.ProfilePhoto().apply {
                small = TdApi.File().apply { local = TdApi.LocalFile().apply { this.path = path } }
            }
        }
    }
}

fun UserFullInfoEntity.toTdApi(): TdApi.UserFullInfo {
    return TdApi.UserFullInfo().apply {
        bio = this@toTdApi.bio?.let { TdApi.FormattedText(it, emptyArray()) }
        groupInCommonCount = commonGroupsCount
        blockList = if (isBlocked) TdApi.BlockListMain() else null
        canBeCalled = this@toTdApi.canBeCalled
        supportsVideoCalls = this@toTdApi.supportsVideoCalls
        hasPrivateCalls = this@toTdApi.hasPrivateCalls
        hasPrivateForwards = this@toTdApi.hasPrivateForwards
    }
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
                userId = if (this@toTdApiChat.privateUserId != 0L) this@toTdApiChat.privateUserId else (this@toTdApiChat.messageSenderId ?: 0L)
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
    return ChatFullInfoModel(
        description = description,
        inviteLink = inviteLink,
        memberCount = memberCount,
        administratorCount = administratorCount,
        restrictedCount = restrictedCount,
        bannedCount = bannedCount,
        commonGroupsCount = commonGroupsCount,
        giftCount = giftCount,
        isBlocked = isBlocked,
        botInfo = botInfo,
        canGetRevenueStatistics = canGetRevenueStatistics,
        linkedChatId = linkedChatId,
        businessInfo = null,
        canBeCalled = canBeCalled,
        supportsVideoCalls = supportsVideoCalls,
        hasPrivateCalls = hasPrivateCalls,
        hasPrivateForwards = hasPrivateForwards,
        hasRestrictedVoiceAndVideoNoteMessages = hasRestrictedVoiceAndVideoNoteMessages,
        hasPostedToProfileStories = hasPostedToProfileStories,
        setChatBackground = setChatBackground,
        slowModeDelay = slowModeDelay,
        locationAddress = locationAddress,
        canSetStickerSet = canSetStickerSet,
        canSetLocation = canSetLocation,
        canGetMembers = canGetMembers,
        canGetStatistics = canGetStatistics,
        incomingPaidMessageStarCount = incomingPaidMessageStarCount,
        outgoingPaidMessageStarCount = outgoingPaidMessageStarCount
    )
}