package org.monogram.data.datasource.remote

import org.drinkless.tdlib.TdApi
import org.monogram.data.core.coRunCatching
import org.monogram.data.gateway.TelegramGateway

class TdLinkRemoteDataSource(
    private val gateway: TelegramGateway
) : LinkRemoteDataSource {

    override suspend fun getInternalLinkType(url: String): TdApi.InternalLinkType? =
        coRunCatching { gateway.execute(TdApi.GetInternalLinkType(url)) }.getOrNull()

    override suspend fun getMessageLinkInfo(url: String): TdApi.MessageLinkInfo? =
        coRunCatching { gateway.execute(TdApi.GetMessageLinkInfo(url)) }.getOrNull()

    override suspend fun searchPublicChat(username: String): TdApi.Chat? =
        coRunCatching { gateway.execute(TdApi.SearchPublicChat(username)) }.getOrNull()

    override suspend fun checkChatInviteLink(inviteLink: String): TdApi.ChatInviteLinkInfo? =
        coRunCatching { gateway.execute(TdApi.CheckChatInviteLink(inviteLink)) }.getOrNull()

    override suspend fun joinChatByInviteLink(inviteLink: String): TdApi.Chat? =
        coRunCatching { gateway.execute(TdApi.JoinChatByInviteLink(inviteLink)) }.getOrNull()

    override suspend fun getMe(): TdApi.User? =
        coRunCatching { gateway.execute(TdApi.GetMe()) }.getOrNull()

    override suspend fun createPrivateChat(userId: Long): TdApi.Chat? {
        if (userId == 0L) return null
        return coRunCatching { gateway.execute(TdApi.CreatePrivateChat(userId, false)) }.getOrNull()
    }

    override suspend fun searchUserByPhoneNumber(phoneNumber: String): TdApi.User? {
        if (phoneNumber.isBlank()) return null
        return coRunCatching {
            gateway.execute(TdApi.SearchUserByPhoneNumber(phoneNumber, true))
        }.getOrNull()
    }

    override suspend fun searchUserByToken(token: String): TdApi.User? {
        if (token.isBlank()) return null
        return coRunCatching { gateway.execute(TdApi.SearchUserByToken(token)) }.getOrNull()
    }
}