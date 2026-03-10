package org.monogram.data.mapper.user

import org.drinkless.tdlib.TdApi
import org.monogram.domain.models.BirthdateModel
import org.monogram.domain.models.BusinessInfoModel
import org.monogram.domain.models.BusinessLocationModel
import org.monogram.domain.models.BusinessOpeningHoursIntervalModel
import org.monogram.domain.models.BusinessOpeningHoursModel
import org.monogram.domain.models.BusinessStartPageModel
import org.monogram.domain.models.ChatFullInfoModel
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.ChatPermissionsModel
import org.monogram.domain.models.ChatType
import org.monogram.domain.models.GroupMemberModel
import org.monogram.domain.models.UserModel
import org.monogram.domain.models.UserStatusType
import org.monogram.domain.models.UserTypeEnum
import org.monogram.domain.models.UsernamesModel
import org.monogram.domain.repository.ChatMemberStatus
import org.monogram.domain.repository.ChatMembersFilter

fun TdApi.User.toDomain(fullInfo: TdApi.UserFullInfo? = null): UserModel {
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
        statusEmojiPath = null,
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
    val type = emojiStatus?.type ?: return 0L
    return (type as? TdApi.EmojiStatusTypeCustomEmoji)?.customEmojiId ?: 0L
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
        canEditTag = canEditTag,
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