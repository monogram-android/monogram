package org.monogram.data.gateway

import kotlinx.coroutines.flow.Flow
import org.drinkless.tdlib.TdApi

interface UpdateDispatcher {
    val all: Flow<TdApi.Update>

    // Auth
    val authorizationState: Flow<TdApi.UpdateAuthorizationState>

    // Messages
    val newMessage: Flow<TdApi.UpdateNewMessage>
    val messageEdited: Flow<TdApi.UpdateMessageEdited>
    val messageContent: Flow<TdApi.UpdateMessageContent>
    val messageSendSucceeded: Flow<TdApi.UpdateMessageSendSucceeded>
    val messageSendFailed: Flow<TdApi.UpdateMessageSendFailed>
    val messageDeleted: Flow<TdApi.UpdateDeleteMessages>
    val messagePinned: Flow<TdApi.UpdateChatLastMessage>
    val messageInteractionInfo: Flow<TdApi.UpdateMessageInteractionInfo>

    // Chats
    val chatLastMessage: Flow<TdApi.UpdateChatLastMessage>
    val chatPosition: Flow<TdApi.UpdateChatPosition>
    val chatReadInbox: Flow<TdApi.UpdateChatReadInbox>
    val chatReadOutbox: Flow<TdApi.UpdateChatReadOutbox>
    val chatUnreadMentionCount: Flow<TdApi.UpdateChatUnreadMentionCount>
    val chatNotificationSettings: Flow<TdApi.UpdateChatNotificationSettings>
    val chatTitle: Flow<TdApi.UpdateChatTitle>
    val chatPhoto: Flow<TdApi.UpdateChatPhoto>
    val chatPermissions: Flow<TdApi.UpdateChatPermissions>
    val chatDraftMessage: Flow<TdApi.UpdateChatDraftMessage>
    val chatAction: Flow<TdApi.UpdateChatAction>
    val chatOnlineMemberCount: Flow<TdApi.UpdateChatOnlineMemberCount>
    val chatFolders: Flow<TdApi.UpdateChatFolders>

    // Users
    val userStatus: Flow<TdApi.UpdateUserStatus>
    val user: Flow<TdApi.UpdateUser>
    val userPrivacySettingRules: Flow<TdApi.UpdateUserPrivacySettingRules>

    // Files
    val file: Flow<TdApi.UpdateFile>

    // Connection
    val connectionState: Flow<TdApi.UpdateConnectionState>
    val installedStickerSets: Flow<TdApi.UpdateInstalledStickerSets>
    val newChat: Flow<TdApi.UpdateNewChat>
    val attachmentMenuBots: Flow<TdApi.UpdateAttachmentMenuBots>

    val chatsListUpdates: Flow<TdApi.Update>
}