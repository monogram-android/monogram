package org.monogram.domain.repository

import org.monogram.domain.models.ChatFullInfoModel
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.ChatPermissionsModel
import org.monogram.domain.models.GroupMemberModel

interface ChatInfoRepository {
    suspend fun getChatFullInfo(chatId: Long): ChatFullInfoModel?
    suspend fun searchPublicChat(username: String): ChatModel?
    suspend fun getChatMembers(
        chatId: Long,
        offset: Int,
        limit: Int,
        filter: ChatMembersFilter = ChatMembersFilter.Recent
    ): List<GroupMemberModel>

    suspend fun getChatMember(chatId: Long, userId: Long): GroupMemberModel?
    suspend fun setChatMemberStatus(chatId: Long, userId: Long, status: ChatMemberStatus)
}

sealed class ChatMembersFilter {
    data object Recent : ChatMembersFilter()
    data object Administrators : ChatMembersFilter()
    data object Banned : ChatMembersFilter()
    data object Restricted : ChatMembersFilter()
    data object Bots : ChatMembersFilter()
    data class Search(val query: String) : ChatMembersFilter()
}

sealed class ChatMemberStatus {
    data object Member : ChatMemberStatus()
    data class Administrator(
        val customTitle: String = "",
        val canBeEdited: Boolean = true,
        val canManageChat: Boolean = true,
        val canChangeInfo: Boolean = true,
        val canPostMessages: Boolean = true,
        val canEditMessages: Boolean = true,
        val canDeleteMessages: Boolean = true,
        val canInviteUsers: Boolean = true,
        val canRestrictMembers: Boolean = true,
        val canPinMessages: Boolean = true,
        val canPromoteMembers: Boolean = true,
        val canManageVideoChats: Boolean = true,
        val canManageTopics: Boolean = true,
        val canPostStories: Boolean = true,
        val canEditStories: Boolean = true,
        val canDeleteStories: Boolean = true,
        val canManageDirectMessages: Boolean = true,
        val isAnonymous: Boolean = false
    ) : ChatMemberStatus()

    data class Restricted(
        val isMember: Boolean = true,
        val restrictedUntilDate: Int = 0,
        val permissions: ChatPermissionsModel = ChatPermissionsModel()
    ) : ChatMemberStatus()

    data object Left : ChatMemberStatus()
    data class Banned(val bannedUntilDate: Int = 0) : ChatMemberStatus()
    data object Creator : ChatMemberStatus()
}