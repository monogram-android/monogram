package org.monogram.data.datasource.remote

import org.drinkless.tdlib.TdApi

interface UserRemoteDataSource {
    suspend fun getUser(userId: Long): TdApi.User?
    suspend fun getMe(): TdApi.User?
    suspend fun getUserFullInfo(userId: Long): TdApi.UserFullInfo?
    suspend fun getSupergroupFullInfo(supergroupId: Long): TdApi.SupergroupFullInfo?
    suspend fun getBasicGroupFullInfo(basicGroupId: Long): TdApi.BasicGroupFullInfo?
    suspend fun getSupergroup(supergroupId: Long): TdApi.Supergroup?
    suspend fun getChat(chatId: Long): TdApi.Chat?
    suspend fun getMessage(chatId: Long, messageId: Long): TdApi.Message?
    suspend fun getUserProfilePhotos(userId: Long, offset: Int, limit: Int): TdApi.ChatPhotos?
    suspend fun getContacts(): TdApi.Users?
    suspend fun searchContacts(query: String): TdApi.Users?
    suspend fun addContact(userId: Long, contact: TdApi.ImportedContact, sharePhoneNumber: Boolean)
    suspend fun removeContacts(userIds: LongArray)
    suspend fun getCloseFriendIds(): LongArray
    suspend fun setCloseFriendIds(userIds: LongArray)
    suspend fun sharePhoneNumber(userId: Long)
    suspend fun searchPublicChat(username: String): TdApi.Chat?
    suspend fun getChatMember(chatId: Long, userId: Long): TdApi.ChatMember?
    suspend fun getSupergroupMembers(
        supergroupId: Long,
        filter: TdApi.SupergroupMembersFilter,
        offset: Int,
        limit: Int
    ): TdApi.ChatMembers?
    suspend fun getBasicGroupMembers(basicGroupId: Long): TdApi.BasicGroupFullInfo?
    suspend fun getPremiumState(): TdApi.PremiumState?
    suspend fun getPremiumFeatures(source: TdApi.PremiumSource): TdApi.PremiumFeatures?
    suspend fun getPremiumLimit(limitType: TdApi.PremiumLimitType): TdApi.PremiumLimit?
    suspend fun setSponsoredMessagesEnabled(enabled: Boolean)
    suspend fun getBotFullInfo(userId: Long): TdApi.UserFullInfo?
    suspend fun getChatStatistics(chatId: Long, isDark: Boolean): TdApi.ChatStatistics?
    suspend fun getChatRevenueStatistics(chatId: Long, isDark: Boolean): TdApi.ChatRevenueStatistics?
    suspend fun getStatisticsGraph(chatId: Long, token: String, x: Long): TdApi.StatisticalGraph?

    suspend fun logout()
    suspend fun setName(firstName: String, lastName: String)
    suspend fun setBio(bio: String)
    suspend fun setUsername(username: String)
    suspend fun setEmojiStatus(customEmojiId: Long?)
    suspend fun setProfilePhoto(path: String)
    suspend fun setBirthdate(birthdate: TdApi.Birthdate?)
    suspend fun setPersonalChat(chatId: Long)
    suspend fun setBusinessBio(bio: String)
    suspend fun setBusinessLocation(location: TdApi.BusinessLocation?)
    suspend fun setBusinessOpeningHours(hours: TdApi.BusinessOpeningHours?)
    suspend fun toggleUsernameIsActive(username: String, isActive: Boolean)
    suspend fun reorderActiveUsernames(usernames: Array<String>)
    suspend fun setChatMemberStatus(
        chatId: Long,
        userId: Long,
        status: TdApi.ChatMemberStatus
    )
}