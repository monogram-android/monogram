package org.monogram.domain.repository

import kotlinx.coroutines.flow.StateFlow

interface ChatOperationsRepository {
    val isArchivePinned: StateFlow<Boolean>
    val isArchiveAlwaysVisible: StateFlow<Boolean>

    suspend fun toggleMuteChats(chatIds: Set<Long>, mute: Boolean)
    suspend fun toggleArchiveChats(chatIds: Set<Long>, archive: Boolean)
    suspend fun togglePinChats(chatIds: Set<Long>, pin: Boolean, folderId: Int)
    suspend fun toggleReadChats(chatIds: Set<Long>, markAsUnread: Boolean)
    fun markChatsAsRead(chatIds: Set<Long>)
    fun markFolderAsRead(folderId: Int, chatIds: Set<Long>)
    suspend fun deleteChats(chatIds: Set<Long>)
    suspend fun leaveChats(chatIds: Set<Long>)
    suspend fun leaveChat(chatId: Long)
    fun setArchivePinned(pinned: Boolean)

    suspend fun clearChatHistories(chatIds: Set<Long>, revoke: Boolean)
    suspend fun clearChatHistory(chatId: Long, revoke: Boolean)
    suspend fun getChatLink(chatId: Long): String?
    suspend fun reportChats(
        chatIds: Set<Long>,
        reason: String,
        messageIds: List<Long> = emptyList()
    )

    suspend fun reportChat(chatId: Long, reason: String, messageIds: List<Long> = emptyList())
}
