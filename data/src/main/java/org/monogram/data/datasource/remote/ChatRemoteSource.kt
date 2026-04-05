package org.monogram.data.datasource.remote

import org.drinkless.tdlib.TdApi
import org.monogram.domain.models.ChatPermissionsModel

interface ChatRemoteSource {
    suspend fun loadChats(chatList: TdApi.ChatList, limit: Int)
    suspend fun searchChats(query: String, limit: Int): TdApi.Chats?
    suspend fun searchPublicChats(query: String): TdApi.Chats?
    suspend fun searchMessages(query: String, offset: String, limit: Int): TdApi.FoundMessages?
    suspend fun getChat(chatId: Long): TdApi.Chat?
    suspend fun getUser(userId: Long): TdApi.User?
    suspend fun createGroup(title: String, userIds: List<Long>, messageAutoDeleteTime: Int): Long
    suspend fun createChannel(title: String, description: String, isMegagroup: Boolean, messageAutoDeleteTime: Int): Long
    suspend fun setChatPhoto(chatId: Long, photoPath: String)
    suspend fun setChatTitle(chatId: Long, title: String)
    suspend fun setChatDescription(chatId: Long, description: String)
    suspend fun setChatUsername(chatId: Long, username: String)
    suspend fun setChatPermissions(chatId: Long, permissions: ChatPermissionsModel)
    suspend fun setChatSlowModeDelay(chatId: Long, slowModeDelay: Int)
    suspend fun toggleChatIsForum(chatId: Long, isForum: Boolean)
    suspend fun toggleChatIsTranslatable(chatId: Long, isTranslatable: Boolean)
    suspend fun getChatLink(chatId: Long): String?
    suspend fun deleteFolder(folderId: Int)
    suspend fun muteChat(chatId: Long, muteFor: Int)
    suspend fun archiveChat(chatId: Long, archive: Boolean)
    suspend fun toggleChatIsPinned(chatList: TdApi.ChatList, chatId: Long, isPinned: Boolean)
    suspend fun toggleChatIsMarkedAsUnread(chatId: Long, isMarkedAsUnread: Boolean)
    suspend fun deleteChat(chatId: Long)
    suspend fun leaveChat(chatId: Long)
    suspend fun clearChatHistory(chatId: Long, revoke: Boolean)
    suspend fun reportChat(chatId: Long, reason: String, messageIds: List<Long>)
    suspend fun getMyUserId(): Long
    suspend fun setNetworkType(): Boolean
    suspend fun getConnectionState(): TdApi.ConnectionState?
    suspend fun getForumTopics(
        chatId: Long,
        query: String,
        offsetDate: Int,
        offsetMessageId: Long,
        offsetForumTopicId: Int,
        limit: Int
    ): TdApi.ForumTopics?
}
