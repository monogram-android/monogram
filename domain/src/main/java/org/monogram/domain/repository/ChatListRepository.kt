package org.monogram.domain.repository

import kotlinx.coroutines.flow.StateFlow
import org.monogram.domain.models.ChatModel

interface ChatListRepository {
    val chatListFlow: StateFlow<List<ChatModel>>
    val isLoadingFlow: StateFlow<Boolean>
    val connectionStateFlow: StateFlow<ConnectionStatus>

    fun loadNextChunk(limit: Int)
    fun selectFolder(folderId: Int)
    fun refresh()
    suspend fun getChatById(chatId: Long): ChatModel?
    fun retryConnection()
}