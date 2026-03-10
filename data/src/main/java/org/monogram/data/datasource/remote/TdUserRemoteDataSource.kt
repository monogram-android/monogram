package org.monogram.data.datasource.remote

import org.drinkless.tdlib.TdApi
import org.monogram.data.gateway.TelegramGateway

class TdUserRemoteDataSource(
    private val gateway: TelegramGateway
) : UserRemoteDataSource {

    override suspend fun getUser(userId: Long): TdApi.User? {
        if (userId == 0L) return null
        return runCatching { gateway.execute(TdApi.GetUser(userId)) }.getOrNull()
    }

    override suspend fun getMe(): TdApi.User? =
        runCatching { gateway.execute(TdApi.GetMe()) }.getOrNull()

    override suspend fun getUserFullInfo(userId: Long): TdApi.UserFullInfo? {
        if (userId == 0L) return null
        return runCatching { gateway.execute(TdApi.GetUserFullInfo(userId)) }.getOrNull()
    }

    override suspend fun getSupergroupFullInfo(supergroupId: Long): TdApi.SupergroupFullInfo? =
        runCatching { gateway.execute(TdApi.GetSupergroupFullInfo(supergroupId)) }.getOrNull()

    override suspend fun getBasicGroupFullInfo(basicGroupId: Long): TdApi.BasicGroupFullInfo? =
        runCatching { gateway.execute(TdApi.GetBasicGroupFullInfo(basicGroupId)) }.getOrNull()

    override suspend fun getSupergroup(supergroupId: Long): TdApi.Supergroup? =
        runCatching { gateway.execute(TdApi.GetSupergroup(supergroupId)) }.getOrNull()

    override suspend fun getChat(chatId: Long): TdApi.Chat? {
        if (chatId == 0L) return null
        return runCatching { gateway.execute(TdApi.GetChat(chatId)) }.getOrNull()
    }

    override suspend fun getMessage(chatId: Long, messageId: Long): TdApi.Message? =
        runCatching { gateway.execute(TdApi.GetMessage(chatId, messageId)) }.getOrNull()

    override suspend fun getUserProfilePhotos(
        userId: Long,
        offset: Int,
        limit: Int
    ): TdApi.ChatPhotos? =
        runCatching { gateway.execute(TdApi.GetUserProfilePhotos(userId, offset, limit)) }.getOrNull()

    override suspend fun getContacts(): TdApi.Users? =
        runCatching { gateway.execute(TdApi.GetContacts()) }.getOrNull()

    override suspend fun searchContacts(query: String): TdApi.Users? =
        runCatching { gateway.execute(TdApi.SearchContacts(query, 50)) }.getOrNull()

    override suspend fun searchPublicChat(username: String): TdApi.Chat? =
        runCatching { gateway.execute(TdApi.SearchPublicChat(username)) }.getOrNull()

    override suspend fun getChatMember(chatId: Long, userId: Long): TdApi.ChatMember? =
        runCatching {
            val chat = gateway.execute(TdApi.GetChat(chatId))
            val type = chat.type
            if (type is TdApi.ChatTypeSupergroup) {
                val supergroup = gateway.execute(TdApi.GetSupergroup(type.supergroupId))
                if (supergroup.isChannel) {
                    val me = gateway.execute(TdApi.GetMe())
                    if (userId != me.id) {
                        val status = supergroup.status
                        val canGetOthers = status is TdApi.ChatMemberStatusAdministrator ||
                                status is TdApi.ChatMemberStatusCreator
                        if (!canGetOthers) return null
                    }
                }
            }
            gateway.execute(TdApi.GetChatMember(chatId, TdApi.MessageSenderUser(userId)))
        }.getOrElse { e ->
            // Handle 400 CHANNEL_PRIVATE and other errors gracefully
            null
        }

    override suspend fun getSupergroupMembers(
        supergroupId: Long,
        filter: TdApi.SupergroupMembersFilter,
        offset: Int,
        limit: Int
    ): TdApi.ChatMembers? =
        runCatching {
            gateway.execute(TdApi.GetSupergroupMembers(supergroupId, filter, offset, limit))
        }.getOrNull()

    override suspend fun getBasicGroupMembers(basicGroupId: Long): TdApi.BasicGroupFullInfo? =
        runCatching { gateway.execute(TdApi.GetBasicGroupFullInfo(basicGroupId)) }.getOrNull()

    override suspend fun getPremiumState(): TdApi.PremiumState? =
        runCatching { gateway.execute(TdApi.GetPremiumState()) }.getOrNull()

    override suspend fun getPremiumFeatures(source: TdApi.PremiumSource): TdApi.PremiumFeatures? =
        runCatching { gateway.execute(TdApi.GetPremiumFeatures(source)) }.getOrNull()

    override suspend fun getPremiumLimit(limitType: TdApi.PremiumLimitType): TdApi.PremiumLimit? =
        runCatching { gateway.execute(TdApi.GetPremiumLimit(limitType)) }.getOrNull()

    override suspend fun getBotFullInfo(userId: Long): TdApi.UserFullInfo? {
        if (userId == 0L) return null
        return runCatching { gateway.execute(TdApi.GetUserFullInfo(userId)) }.getOrNull()
    }

    override suspend fun getChatStatistics(chatId: Long, isDark: Boolean): TdApi.ChatStatistics? =
        runCatching { gateway.execute(TdApi.GetChatStatistics(chatId, isDark)) }.getOrNull()

    override suspend fun getChatRevenueStatistics(
        chatId: Long,
        isDark: Boolean
    ): TdApi.ChatRevenueStatistics? =
        runCatching {
            gateway.execute(TdApi.GetChatRevenueStatistics(chatId, isDark))
        }.getOrNull()

    override suspend fun getStatisticsGraph(
        chatId: Long,
        token: String,
        x: Long
    ): TdApi.StatisticalGraph? =
        runCatching { gateway.execute(TdApi.GetStatisticalGraph(chatId, token, x)) }.getOrNull()

    override suspend fun logout() {
        gateway.execute(TdApi.LogOut())
    }

    override suspend fun setName(firstName: String, lastName: String) {
        gateway.execute(TdApi.SetName(firstName, lastName))
    }

    override suspend fun setBio(bio: String) {
        gateway.execute(TdApi.SetBio(bio))
    }

    override suspend fun setUsername(username: String) {
        gateway.execute(TdApi.SetUsername(username))
    }

    override suspend fun setProfilePhoto(path: String) {
        gateway.execute(
            TdApi.SetProfilePhoto(TdApi.InputChatPhotoStatic(TdApi.InputFileLocal(path)), true)
        )
    }

    override suspend fun setBirthdate(birthdate: TdApi.Birthdate?) {
        gateway.execute(TdApi.SetBirthdate(birthdate))
    }

    override suspend fun setPersonalChat(chatId: Long) {
        gateway.execute(TdApi.SetPersonalChat(chatId))
    }

    override suspend fun setBusinessBio(bio: String) {
        gateway.execute(TdApi.SetBusinessAccountBio("", bio))
    }

    override suspend fun setBusinessLocation(location: TdApi.BusinessLocation?) {
        gateway.execute(TdApi.SetBusinessLocation(location))
    }

    override suspend fun setBusinessOpeningHours(hours: TdApi.BusinessOpeningHours?) {
        gateway.execute(TdApi.SetBusinessOpeningHours(hours))
    }

    override suspend fun toggleUsernameIsActive(username: String, isActive: Boolean) {
        gateway.execute(TdApi.ToggleUsernameIsActive(username, isActive))
    }

    override suspend fun reorderActiveUsernames(usernames: Array<String>) {
        gateway.execute(TdApi.ReorderActiveUsernames(usernames))
    }

    override suspend fun setChatMemberStatus(
        chatId: Long,
        userId: Long,
        status: TdApi.ChatMemberStatus
    ) {
        gateway.execute(TdApi.SetChatMemberStatus(chatId, TdApi.MessageSenderUser(userId), status))
    }
}