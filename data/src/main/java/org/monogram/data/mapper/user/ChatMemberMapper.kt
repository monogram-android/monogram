package org.monogram.data.mapper.user

import org.drinkless.tdlib.TdApi
import org.monogram.data.mapper.toDomainChatPermissions
import org.monogram.data.mapper.toTdApiChatPermissions
import org.monogram.domain.models.GroupMemberModel
import org.monogram.domain.models.UserModel
import org.monogram.domain.repository.ChatMemberStatus
import org.monogram.domain.repository.ChatMembersFilter

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
            permissions = permissions.toDomainChatPermissions()
        )

        is TdApi.ChatMemberStatusBanned -> ChatMemberStatus.Banned(bannedUntilDate)
        is TdApi.ChatMemberStatusLeft -> ChatMemberStatus.Left
        else -> ChatMemberStatus.Member
    }
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
            permissions.toTdApiChatPermissions()
        )

        is ChatMemberStatus.Left -> TdApi.ChatMemberStatusLeft()
        is ChatMemberStatus.Banned -> TdApi.ChatMemberStatusBanned(bannedUntilDate)
        is ChatMemberStatus.Creator -> TdApi.ChatMemberStatusCreator(false, true)
    }
}
