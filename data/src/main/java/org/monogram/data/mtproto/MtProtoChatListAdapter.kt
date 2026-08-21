package org.monogram.data.mtproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.FolderModel
import org.monogram.domain.repository.ConnectionStatus
import org.monogram.domain.repository.ChatFolderRepository
import org.monogram.domain.repository.ChatListRepository
import org.monogram.domain.repository.ChatOperationsRepository
import org.monogram.domain.repository.FolderChatsUpdate
import org.monogram.domain.repository.FolderLoadingUpdate

/**
 * Adapts the persisted MTProto dialog projection into the chat list contracts, mirroring
 * repository flows into state flows for presentation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class MtProtoChatListAdapter(
    private val mtProtoFactory: () -> Contracts,
    scope: CoroutineScope,
) : ChatListRepository, ChatFolderRepository, ChatOperationsRepository {
    private val _chatListFlow = MutableStateFlow<List<ChatModel>>(emptyList())
    override val chatListFlow: StateFlow<List<ChatModel>> = _chatListFlow.asStateFlow()
    private val _isLoadingFlow = MutableStateFlow(false)
    override val isLoadingFlow: StateFlow<Boolean> = _isLoadingFlow.asStateFlow()
    private val _connectionStateFlow = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connecting)
    override val connectionStateFlow: StateFlow<ConnectionStatus> = _connectionStateFlow.asStateFlow()
    private val _folderChatsFlow = MutableSharedFlow<FolderChatsUpdate>(replay = 1, extraBufferCapacity = 1)
    override val folderChatsFlow: Flow<FolderChatsUpdate> = _folderChatsFlow.asSharedFlow()
    private val _foldersFlow = MutableStateFlow<List<FolderModel>>(emptyList())
    override val foldersFlow: StateFlow<List<FolderModel>> = _foldersFlow.asStateFlow()
    private val _folderLoadingFlow = MutableSharedFlow<FolderLoadingUpdate>(replay = 1, extraBufferCapacity = 1)
    override val folderLoadingFlow: Flow<FolderLoadingUpdate> = _folderLoadingFlow.asSharedFlow()
    private val _isArchivePinned = MutableStateFlow(false)
    override val isArchivePinned: StateFlow<Boolean> = _isArchivePinned.asStateFlow()
    private val _isArchiveAlwaysVisible = MutableStateFlow(false)
    override val isArchiveAlwaysVisible: StateFlow<Boolean> = _isArchiveAlwaysVisible.asStateFlow()
    private val mtProto by lazy(LazyThreadSafetyMode.NONE, mtProtoFactory)

    init {
        scope.launch { mtProto.chatListFlow.collect { _chatListFlow.value = it } }
        scope.launch { mtProto.isLoadingFlow.collect { _isLoadingFlow.value = it } }
        scope.launch { mtProto.connectionStateFlow.collect { _connectionStateFlow.value = it } }
        scope.launch { mtProto.folderChatsFlow.collect { _folderChatsFlow.emit(it) } }
        scope.launch { mtProto.foldersFlow.collect { _foldersFlow.value = it } }
        scope.launch { mtProto.folderLoadingFlow.collect { _folderLoadingFlow.emit(it) } }
        scope.launch { mtProto.isArchivePinned.collect { _isArchivePinned.value = it } }
        scope.launch { mtProto.isArchiveAlwaysVisible.collect { _isArchiveAlwaysVisible.value = it } }
    }

    override fun loadNextChunk(limit: Int) = mtProto.loadNextChunk(limit)
    override fun selectFolder(folderId: Int) = mtProto.selectFolder(folderId)
    override fun refresh() = mtProto.refresh()
    override fun refreshOnResume() = mtProto.refreshOnResume()
    override suspend fun getChatById(chatId: Long): ChatModel? = mtProto.getChatById(chatId)
    override suspend fun isChatArchived(chatId: Long): Boolean? = mtProto.isChatArchived(chatId)
    override fun retryConnection() = mtProto.retryConnection()
    override suspend fun createFolder(title: String, iconName: String?, includedChatIds: List<Long>) =
        mtProto.createFolder(title, iconName, includedChatIds)
    override suspend fun deleteFolder(folderId: Int) = mtProto.deleteFolder(folderId)
    override suspend fun updateFolder(folderId: Int, title: String, iconName: String?, includedChatIds: List<Long>) =
        mtProto.updateFolder(folderId, title, iconName, includedChatIds)
    override suspend fun reorderFolders(folderIds: List<Int>) = mtProto.reorderFolders(folderIds)
    override suspend fun toggleMuteChats(chatIds: Set<Long>, mute: Boolean) = mtProto.toggleMuteChats(chatIds, mute)
    override suspend fun toggleArchiveChats(chatIds: Set<Long>, archive: Boolean) = mtProto.toggleArchiveChats(chatIds, archive)
    override suspend fun togglePinChats(chatIds: Set<Long>, pin: Boolean, folderId: Int) = mtProto.togglePinChats(chatIds, pin, folderId)
    override suspend fun toggleReadChats(chatIds: Set<Long>, markAsUnread: Boolean) = mtProto.toggleReadChats(chatIds, markAsUnread)
    override fun markChatsAsRead(chatIds: Set<Long>) = mtProto.markChatsAsRead(chatIds)
    override fun markFolderAsRead(folderId: Int, chatIds: Set<Long>) = mtProto.markFolderAsRead(folderId, chatIds)
    override suspend fun deleteChats(chatIds: Set<Long>) = mtProto.deleteChats(chatIds)
    override suspend fun leaveChats(chatIds: Set<Long>) = mtProto.leaveChats(chatIds)
    override suspend fun leaveChat(chatId: Long) = mtProto.leaveChat(chatId)
    override fun setArchivePinned(pinned: Boolean) = mtProto.setArchivePinned(pinned)
    override suspend fun clearChatHistories(chatIds: Set<Long>, revoke: Boolean) = mtProto.clearChatHistories(chatIds, revoke)
    override suspend fun clearChatHistory(chatId: Long, revoke: Boolean) = mtProto.clearChatHistory(chatId, revoke)
    override suspend fun getChatLink(chatId: Long): String? = mtProto.getChatLink(chatId)
    override suspend fun reportChats(chatIds: Set<Long>, reason: String, messageIds: List<Long>) = mtProto.reportChats(chatIds, reason, messageIds)
    override suspend fun reportChat(chatId: Long, reason: String, messageIds: List<Long>) = mtProto.reportChat(chatId, reason, messageIds)

    internal interface Contracts : ChatListRepository, ChatFolderRepository, ChatOperationsRepository
}
