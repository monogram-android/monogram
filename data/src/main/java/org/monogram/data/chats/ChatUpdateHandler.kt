package org.monogram.data.chats

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import org.monogram.core.DispatcherProvider
import org.monogram.data.datasource.cache.ChatLocalDataSource
import org.monogram.data.mapper.user.toEntity

class ChatUpdateHandler(
    private val cache: ChatCache,
    private val listManager: ChatListManager,
    private val typingManager: ChatTypingManager,
    private val fileManager: ChatFileManager,
    private val folderManager: ChatFolderManager,
    private val chatLocalDataSource: ChatLocalDataSource,
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope,
    private val activeChatListProvider: () -> TdApi.ChatList,
    private val myUserIdProvider: () -> Long,
    private val onSaveChat: (Long) -> Unit,
    private val onSaveChatsBySupergroupId: (Long) -> Unit,
    private val onSaveChatsByBasicGroupId: (Long) -> Unit,
    private val onTriggerUpdate: (Long?) -> Unit,
    private val onRefreshChat: suspend (Long) -> Unit,
    private val onRefreshForumTopics: () -> Unit,
    private val onAuthorizationStateClosed: () -> Unit
) {
    fun handle(update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateNewChat -> {
                cache.putChat(update.chat)
                listManager.updateActiveListPositions(update.chat.id, update.chat.positions, activeChatListProvider())
                onSaveChat(update.chat.id)
                onTriggerUpdate(update.chat.id)
            }

            is TdApi.UpdateChatTitle -> {
                cache.updateChat(update.chatId) { it.title = update.title }
                onSaveChat(update.chatId)
                onTriggerUpdate(update.chatId)
            }

            is TdApi.UpdateChatPhoto -> {
                cache.updateChat(update.chatId) { it.photo = update.photo }
                onSaveChat(update.chatId)
                onTriggerUpdate(update.chatId)
            }

            is TdApi.UpdateChatEmojiStatus -> {
                cache.updateChat(update.chatId) { it.emojiStatus = update.emojiStatus }
                onSaveChat(update.chatId)
                onTriggerUpdate(update.chatId)
            }

            is TdApi.UpdateChatDraftMessage -> {
                cache.updateChat(update.chatId) { chat ->
                    chat.draftMessage = update.draftMessage
                    if (!update.positions.isNullOrEmpty()) {
                        chat.positions = update.positions
                        listManager.updateActiveListPositions(update.chatId, update.positions, activeChatListProvider())
                    }
                }
                onSaveChat(update.chatId)
                onTriggerUpdate(update.chatId)
            }

            is TdApi.UpdateChatPosition -> {
                if (listManager.updateChatPositionInCache(update.chatId, update.position, activeChatListProvider())) {
                    onSaveChat(update.chatId)
                    onTriggerUpdate(update.chatId)
                }
            }

            is TdApi.UpdateChatLastMessage -> {
                cache.updateChat(update.chatId) { chat ->
                    chat.lastMessage = update.lastMessage
                    if (!update.positions.isNullOrEmpty()) {
                        chat.positions = update.positions
                        listManager.updateActiveListPositions(update.chatId, update.positions, activeChatListProvider())
                    }
                    typingManager.clearTypingStatus(update.chatId)
                }
                onSaveChat(update.chatId)
                onTriggerUpdate(update.chatId)
            }

            is TdApi.UpdateChatReadInbox -> {
                cache.updateChat(update.chatId) { it.unreadCount = update.unreadCount }
                onSaveChat(update.chatId)
                onTriggerUpdate(update.chatId)
            }

            is TdApi.UpdateChatReadOutbox -> {
                cache.updateChat(update.chatId) { it.lastReadOutboxMessageId = update.lastReadOutboxMessageId }
                onSaveChat(update.chatId)
                onTriggerUpdate(update.chatId)
            }

            is TdApi.UpdateChatUnreadMentionCount -> {
                cache.updateChat(update.chatId) { it.unreadMentionCount = update.unreadMentionCount }
                folderManager.handleUpdateChatUnreadCount(update.chatId, update.unreadMentionCount)
                onSaveChat(update.chatId)
                onTriggerUpdate(update.chatId)
            }

            is TdApi.UpdateChatUnreadReactionCount -> {
                cache.updateChat(update.chatId) { it.unreadReactionCount = update.unreadReactionCount }
                onSaveChat(update.chatId)
                onTriggerUpdate(update.chatId)
            }

            is TdApi.UpdateMessageMentionRead -> {
                cache.updateChat(update.chatId) { it.unreadMentionCount = update.unreadMentionCount }
                folderManager.handleUpdateChatUnreadCount(update.chatId, update.unreadMentionCount)
                onSaveChat(update.chatId)
                onTriggerUpdate(update.chatId)
            }

            is TdApi.UpdateMessageReactions -> {
                onSaveChat(update.chatId)
                onTriggerUpdate(update.chatId)
            }

            is TdApi.UpdateFile -> {
                if (fileManager.handleFileUpdate(update.file)) {
                    val chatId = fileManager.getChatIdByPhotoId(update.file.id)
                    onTriggerUpdate(chatId)
                    onRefreshForumTopics()
                }
            }

            is TdApi.UpdateDeleteMessages -> {
                if (update.isPermanent || update.fromCache) {
                    scope.launch { onRefreshChat(update.chatId) }
                }
            }

            is TdApi.UpdateChatFolders -> {
                Log.d(TAG, "UpdateChatFolders received in update handler")
                folderManager.handleChatFoldersUpdate(update)
                onTriggerUpdate(null)
            }

            is TdApi.UpdateUserStatus -> {
                cache.updateUser(update.userId) { it.status = update.status }
                cache.userIdToChatId[update.userId]?.let { chatId ->
                    onTriggerUpdate(chatId)
                }
            }

            is TdApi.UpdateUser -> {
                cache.putUser(update.user)
                val privateChatId = cache.userIdToChatId[update.user.id]
                if (privateChatId != null) {
                    onTriggerUpdate(privateChatId)
                }
                onRefreshForumTopics()
            }

            is TdApi.UpdateSupergroup -> {
                cache.putSupergroup(update.supergroup)
                onSaveChatsBySupergroupId(update.supergroup.id)
                cache.supergroupIdToChatId[update.supergroup.id]?.let { chatId ->
                    onTriggerUpdate(chatId)
                }
            }

            is TdApi.UpdateBasicGroup -> {
                cache.putBasicGroup(update.basicGroup)
                onSaveChatsByBasicGroupId(update.basicGroup.id)
                cache.basicGroupIdToChatId[update.basicGroup.id]?.let { chatId ->
                    onTriggerUpdate(chatId)
                }
            }

            is TdApi.UpdateSupergroupFullInfo -> {
                cache.putSupergroupFullInfo(update.supergroupId, update.supergroupFullInfo)
                val chatId = cache.supergroupIdToChatId[update.supergroupId]
                scope.launch(dispatchers.io) {
                    if (chatId != null) {
                        chatLocalDataSource.insertChatFullInfo(update.supergroupFullInfo.toEntity(chatId))
                    }
                }
                if (chatId != null) {
                    onTriggerUpdate(chatId)
                }
            }

            is TdApi.UpdateBasicGroupFullInfo -> {
                cache.putBasicGroupFullInfo(update.basicGroupId, update.basicGroupFullInfo)
                val chatId = cache.basicGroupIdToChatId[update.basicGroupId]
                scope.launch(dispatchers.io) {
                    if (chatId != null) {
                        chatLocalDataSource.insertChatFullInfo(update.basicGroupFullInfo.toEntity(chatId))
                    }
                }
                if (chatId != null) {
                    onTriggerUpdate(chatId)
                }
            }

            is TdApi.UpdateSecretChat -> {
                cache.putSecretChat(update.secretChat)
                onTriggerUpdate(null)
            }

            is TdApi.UpdateChatAction -> typingManager.handleChatAction(update)

            is TdApi.UpdateChatNotificationSettings -> {
                cache.updateChat(update.chatId) { it.notificationSettings = update.notificationSettings }
                onSaveChat(update.chatId)
                onTriggerUpdate(update.chatId)
            }

            is TdApi.UpdateChatViewAsTopics -> {
                cache.updateChat(update.chatId) { it.viewAsTopics = update.viewAsTopics }
                onSaveChat(update.chatId)
                onTriggerUpdate(update.chatId)
            }

            is TdApi.UpdateChatIsTranslatable -> {
                cache.updateChat(update.chatId) { it.isTranslatable = update.isTranslatable }
                onSaveChat(update.chatId)
                onTriggerUpdate(update.chatId)
            }

            is TdApi.UpdateChatPermissions -> {
                cache.putChatPermissions(update.chatId, update.permissions)
                cache.updateChat(update.chatId) { it.permissions = update.permissions }
                onSaveChat(update.chatId)
                onTriggerUpdate(update.chatId)
            }

            is TdApi.UpdateChatMember -> {
                val memberId = update.newChatMember.memberId
                if (memberId is TdApi.MessageSenderUser && memberId.userId == myUserIdProvider()) {
                    cache.putMyChatMember(update.chatId, update.newChatMember)
                    onTriggerUpdate(update.chatId)
                }
            }

            is TdApi.UpdateChatOnlineMemberCount -> {
                cache.putOnlineMemberCount(update.chatId, update.onlineMemberCount)
                onSaveChat(update.chatId)
                onTriggerUpdate(update.chatId)
            }

            is TdApi.UpdateAuthorizationState -> {
                Log.d(TAG, "UpdateAuthorizationState: ${update.authorizationState}")
                if (update.authorizationState is TdApi.AuthorizationStateLoggingOut ||
                    update.authorizationState is TdApi.AuthorizationStateClosed
                ) {
                    cache.clearAll()
                    onAuthorizationStateClosed()
                }
            }

            else -> {}
        }
    }

    companion object {
        private const val TAG = "ChatUpdateHandler"
    }
}