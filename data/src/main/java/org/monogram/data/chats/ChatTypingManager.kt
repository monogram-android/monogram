package org.monogram.data.chats

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import org.monogram.domain.repository.StringProvider
import java.util.concurrent.ConcurrentHashMap

class ChatTypingManager(
    private val scope: CoroutineScope,
    private val usersCache: Map<Long, TdApi.User>,
    private val allChats: Map<Long, TdApi.Chat>,
    private val stringProvider: StringProvider,
    private val onUpdate: () -> Unit,
    private val onUserNeeded: (Long) -> Unit
) {
    private val typingStates = ConcurrentHashMap<Long, ConcurrentHashMap<Long, String>>()
    private val typingJobs = ConcurrentHashMap<Long, ConcurrentHashMap<Long, Job>>()

    fun handleChatAction(update: TdApi.UpdateChatAction) {
        val chatId = update.chatId
        val action = update.action
        val senderId = update.senderId
        if (senderId !is TdApi.MessageSenderUser) return
        val userId = senderId.userId

        if (action is TdApi.ChatActionCancel) {
            removeTypingUser(chatId, userId)
            onUpdate()
            return
        }

        val actionStringRes = when (action) {
            is TdApi.ChatActionTyping -> "typing_typing"
            is TdApi.ChatActionRecordingVideo -> "typing_recording_video"
            is TdApi.ChatActionRecordingVoiceNote -> "typing_recording_voice"
            is TdApi.ChatActionUploadingPhoto -> "typing_uploading_photo"
            is TdApi.ChatActionUploadingVideo -> "typing_uploading_video"
            is TdApi.ChatActionUploadingDocument -> "typing_uploading_document"
            is TdApi.ChatActionChoosingSticker -> "typing_choosing_sticker"
            is TdApi.ChatActionStartPlayingGame -> "typing_playing_game"
            else -> null
        }

        if (actionStringRes == null) return

        if (usersCache[userId] == null) {
            onUserNeeded(userId)
        }

        val chatTyping = typingStates.getOrPut(chatId) { ConcurrentHashMap() }
        chatTyping[userId] = actionStringRes
        val chatJobs = typingJobs.getOrPut(chatId) { ConcurrentHashMap() }
        chatJobs[userId]?.cancel()
        chatJobs[userId] = scope.launch {
            delay(6000)
            removeTypingUser(chatId, userId)
            onUpdate()
        }
        onUpdate()
    }

    fun removeTypingUser(chatId: Long, userId: Long) {
        typingStates[chatId]?.remove(userId)
        typingJobs[chatId]?.remove(userId)?.cancel()
    }

    fun formatTypingAction(chatId: Long): String? {
        val users = typingStates[chatId] ?: return null
        val activeUserIds = users.keys.toList()
        if (activeUserIds.isEmpty()) return null
        val chat = allChats[chatId] ?: return null
        if (chat.type is TdApi.ChatTypePrivate) {
            return stringProvider.getString(users[activeUserIds[0]]!!)
        }
        return when (val count = activeUserIds.size) {
            1 -> {
                val user = usersCache[activeUserIds[0]]
                val action = stringProvider.getString(users[activeUserIds[0]]!!)
                if (user != null) "${user.firstName} $action"
                else "${stringProvider.getString("typing_someone")} $action"
            }

            2 -> {
                val user1 = usersCache[activeUserIds[0]]
                val user2 = usersCache[activeUserIds[1]]
                val action = stringProvider.getString("typing_multi_typing")
                val and = stringProvider.getString("typing_and")
                if (user1 != null && user2 != null) "${user1.firstName} $and ${user2.firstName} $action"
                else stringProvider.getQuantityString("typing_multi_people", 2, 2)
            }

            else -> {
                val user1 = usersCache[activeUserIds[0]]
                val andMore = stringProvider.getString("typing_and_more", count - 1)
                val action = stringProvider.getString("typing_multi_typing")
                if (user1 != null) "${user1.firstName} $andMore $action"
                else stringProvider.getQuantityString("typing_multi_people", count, count)
            }
        }
    }

    fun clearTypingStatus(chatId: Long) {
        typingStates.remove(chatId)
        typingJobs.remove(chatId)?.values?.forEach { it.cancel() }
    }
}
