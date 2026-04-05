package org.monogram.data.datasource.remote

import org.drinkless.tdlib.TdApi

interface LinkRemoteDataSource {
    suspend fun getInternalLinkType(url: String): TdApi.InternalLinkType?
    suspend fun getMessageLinkInfo(url: String): TdApi.MessageLinkInfo?
    suspend fun searchPublicChat(username: String): TdApi.Chat?
    suspend fun checkChatInviteLink(inviteLink: String): TdApi.ChatInviteLinkInfo?
    suspend fun joinChatByInviteLink(inviteLink: String): TdApi.Chat?
    suspend fun getMe(): TdApi.User?
    suspend fun createPrivateChat(userId: Long): TdApi.Chat?
    suspend fun searchUserByPhoneNumber(phoneNumber: String): TdApi.User?
    suspend fun searchUserByToken(token: String): TdApi.User?
}