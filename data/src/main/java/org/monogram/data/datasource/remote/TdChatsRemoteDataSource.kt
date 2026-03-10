package org.monogram.data.datasource.remote

import android.util.Log
import org.drinkless.tdlib.TdApi
import org.monogram.data.gateway.TelegramGateway

class TdChatsRemoteDataSource(
    private val gateway: TelegramGateway
) : ChatsRemoteDataSource {

    private suspend fun <T : TdApi.Object> safeExecute(function: TdApi.Function<T>): T? {
        return try {
            gateway.execute(function)
        } catch (e: Exception) {
            Log.e("TdChatsRemote", "Error executing ${function.javaClass.simpleName}", e)
            null
        }
    }

    override suspend fun getChat(chatId: Long): TdApi.Chat? {
        val chat = safeExecute(TdApi.GetChat(chatId))
        if (chat == null && chatId > 0) {
            // Try to create private chat if it's a user ID and not found
            return safeExecute(TdApi.CreatePrivateChat(chatId, false))
        }
        return chat
    }

    override suspend fun searchChats(query: String, limit: Int): TdApi.Chats? =
        safeExecute(TdApi.SearchChats(query, limit))

    override suspend fun searchPublicChats(query: String): TdApi.Chats? =
        safeExecute(TdApi.SearchPublicChats(query))

    override suspend fun getChatNotificationSettingsExceptions(
        scope: TdApi.NotificationSettingsScope,
        compareSound: Boolean
    ): TdApi.Chats? =
        safeExecute(TdApi.GetChatNotificationSettingsExceptions(scope, compareSound))

    override suspend fun getForumTopics(
        chatId: Long,
        query: String,
        offsetDate: Int,
        offsetMessageId: Long,
        offsetForumTopicId: Int,
        limit: Int
    ): TdApi.ForumTopics? = safeExecute(
        TdApi.GetForumTopics(chatId, query, offsetDate, offsetMessageId, offsetForumTopicId, limit)
    )
}
