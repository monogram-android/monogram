package org.monogram.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.FolderModel

data class FolderChatsUpdate(
    val folderId: Int,
    val chats: List<ChatModel>
)

data class FolderLoadingUpdate(
    val folderId: Int,
    val isLoading: Boolean
)

interface ChatFolderRepository {
    val folderChatsFlow: Flow<FolderChatsUpdate>
    val foldersFlow: StateFlow<List<FolderModel>>
    val folderLoadingFlow: Flow<FolderLoadingUpdate>

    suspend fun createFolder(title: String, iconName: String?, includedChatIds: List<Long>)
    suspend fun deleteFolder(folderId: Int)
    suspend fun updateFolder(folderId: Int, title: String, iconName: String?, includedChatIds: List<Long>)
    suspend fun reorderFolders(folderIds: List<Int>)
}