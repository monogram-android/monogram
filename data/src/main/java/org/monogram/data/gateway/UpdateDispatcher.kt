package org.monogram.data.gateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

interface UpdateDispatcher {
    /**
     * Observation stream. Conflates when a collector falls behind, so it must only be
     * used by consumers that render state they can re-read. Anything that writes to Room,
     * mutates [org.monogram.data.chats.ChatCache], or issues a TDLib request must use
     * [lane] instead.
     */
    val all: Flow<TdApi.Update>

    /**
     * Lossless, strictly ordered, exception-isolated subscription.
     *
     * The lane owns a private unbounded queue and a private worker, so a slow handler
     * delays only itself, and a handler that throws does not end the subscription.
     * Register during startup: updates delivered before the lane exists are not replayed.
     *
     * @param filter evaluated on the update pump thread; keep it to cheap type checks.
     * @param context extra worker context, e.g. `Dispatchers.IO` for lanes that hit Room.
     */
    fun lane(
        name: String,
        scope: CoroutineScope,
        context: CoroutineContext = EmptyCoroutineContext,
        filter: (TdApi.Update) -> Boolean = { true },
        handler: suspend (TdApi.Update) -> Unit,
    )

    // Auth
    val authorizationState: Flow<TdApi.UpdateAuthorizationState>

    // Messages
    val newMessage: Flow<TdApi.UpdateNewMessage>
    val activeNotifications: Flow<TdApi.UpdateActiveNotifications>
    val notificationGroup: Flow<TdApi.UpdateNotificationGroup>
    val notification: Flow<TdApi.UpdateNotification>
    val messageEdited: Flow<TdApi.UpdateMessageEdited>
    val messageContent: Flow<TdApi.UpdateMessageContent>
    val messageSendAcknowledged: Flow<TdApi.UpdateMessageSendAcknowledged>
        get() = emptyFlow()
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
    val option: Flow<TdApi.UpdateOption>
    val connectionState: Flow<TdApi.UpdateConnectionState>
    val installedStickerSets: Flow<TdApi.UpdateInstalledStickerSets>
    val newChat: Flow<TdApi.UpdateNewChat>
    val attachmentMenuBots: Flow<TdApi.UpdateAttachmentMenuBots>

    val chatsListUpdates: Flow<TdApi.Update>
}