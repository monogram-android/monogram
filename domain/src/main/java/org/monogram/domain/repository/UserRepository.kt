package org.monogram.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.monogram.domain.models.*

interface UserRepository {
    val currentUserFlow: StateFlow<UserModel?>
    val anyUserUpdateFlow: Flow<Long>
    suspend fun getMe(): UserModel
    suspend fun getUser(userId: Long): UserModel?
    suspend fun getUserFullInfo(userId: Long): UserModel?
    fun getUserFlow(userId: Long): Flow<UserModel?>
    suspend fun getUserProfilePhotos(userId: Long, offset: Int = 0, limit: Int = 10): List<String>
    fun getUserProfilePhotosFlow(userId: Long): Flow<List<String>>

    suspend fun getChatFullInfo(chatId: Long): ChatFullInfoModel?

    suspend fun getPremiumState(): PremiumStateModel?
    suspend fun getPremiumFeatures(source: PremiumSource): List<PremiumFeatureType>
    suspend fun getPremiumLimit(limitType: PremiumLimitType): Int
    fun logOut()

    suspend fun getBotCommands(botId: Long): List<BotCommandModel>
    suspend fun getBotInfo(botId: Long): BotInfoModel?

    suspend fun getContacts(): List<UserModel>
    suspend fun searchContacts(query: String): List<UserModel>
    suspend fun getChatMembers(
        chatId: Long,
        offset: Int,
        limit: Int,
        filter: ChatMembersFilter = ChatMembersFilter.Recent
    ): List<GroupMemberModel>

    suspend fun getChatMember(chatId: Long, userId: Long): GroupMemberModel?
    suspend fun searchPublicChat(username: String): ChatModel?

    suspend fun setChatMemberStatus(chatId: Long, userId: Long, status: ChatMemberStatus)

    suspend fun getChatStatistics(chatId: Long, isDark: Boolean): ChatStatisticsModel?
    suspend fun getChatRevenueStatistics(chatId: Long, isDark: Boolean): ChatRevenueStatisticsModel?
    suspend fun loadStatisticsGraph(chatId: Long, token: String, x: Long): StatisticsGraphModel?

    suspend fun setName(firstName: String, lastName: String)
    suspend fun setBio(bio: String)
    suspend fun setUsername(username: String)
    suspend fun setEmojiStatus(customEmojiId: Long?)
    suspend fun setProfilePhoto(path: String)
    suspend fun setBirthdate(birthdate: BirthdateModel?)
    suspend fun setPersonalChat(chatId: Long)
    suspend fun setBusinessBio(bio: String)
    suspend fun setBusinessLocation(address: String, latitude: Double = 0.0, longitude: Double = 0.0)
    suspend fun setBusinessOpeningHours(openingHours: BusinessOpeningHoursModel?)
    suspend fun toggleUsernameIsActive(username: String, isActive: Boolean)
    suspend fun reorderActiveUsernames(usernames: List<String>)
    fun forceSponsorSync()
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
