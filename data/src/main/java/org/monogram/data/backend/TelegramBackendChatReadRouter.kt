package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.FolderModel
import org.monogram.domain.repository.ChatFolderRepository
import org.monogram.domain.repository.ChatListRepository
import org.monogram.domain.repository.ChatOperationsRepository
import org.monogram.domain.repository.ConnectionStatus
import org.monogram.domain.repository.FolderChatsUpdate
import org.monogram.domain.repository.FolderLoadingUpdate

/**
 * Routes only the chat-list contracts needed while the list screen starts.
 *
 * Legacy and MTProto implementations are factories so the inactive backend is never constructed
 * just because presentation asks for a shared repository interface.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class TelegramBackendChatReadRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> ChatReadContracts,
    private val mtProtoFactory: () -> ChatReadContracts,
    scope: CoroutineScope,
    accountId: String = DEFAULT_ACCOUNT_ID,
) : ChatListRepository, ChatFolderRepository, ChatOperationsRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
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

    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)
    private val mtProto by lazy(LazyThreadSafetyMode.NONE, mtProtoFactory)

    init {
        scope.launch {
            selectionStore.observe(accountId)
                .distinctUntilChanged()
                .collect { selectedBackend.value = it }
        }
        scope.launch { observe { it.chatListFlow }.collect { _chatListFlow.value = it } }
        scope.launch { observe { it.isLoadingFlow }.collect { _isLoadingFlow.value = it } }
        scope.launch { observe { it.connectionStateFlow }.collect { _connectionStateFlow.value = it } }
        scope.launch { observe { it.folderChatsFlow }.collect { _folderChatsFlow.emit(it) } }
        scope.launch { observe { it.foldersFlow }.collect { _foldersFlow.value = it } }
        scope.launch { observe { it.folderLoadingFlow }.collect { _folderLoadingFlow.emit(it) } }
        scope.launch { observe { it.isArchivePinned }.collect { _isArchivePinned.value = it } }
        scope.launch { observe { it.isArchiveAlwaysVisible }.collect { _isArchiveAlwaysVisible.value = it } }
    }

    override fun loadNextChunk(limit: Int) = selectedContracts()?.loadNextChunk(limit) ?: Unit
    override fun selectFolder(folderId: Int) = selectedContracts()?.selectFolder(folderId) ?: Unit
    override fun refresh() = selectedContracts()?.refresh() ?: Unit
    override fun refreshOnResume() = selectedContracts()?.refreshOnResume() ?: Unit
    override suspend fun getChatById(chatId: Long): ChatModel? = selectedContracts()?.getChatById(chatId)
    override suspend fun isChatArchived(chatId: Long): Boolean? = selectedContracts()?.isChatArchived(chatId)
    override fun retryConnection() = selectedContracts()?.retryConnection() ?: Unit
    override suspend fun createFolder(title: String, iconName: String?, includedChatIds: List<Long>) =
        selectedContractsOrThrow().createFolder(title, iconName, includedChatIds)
    override suspend fun deleteFolder(folderId: Int) = selectedContractsOrThrow().deleteFolder(folderId)
    override suspend fun updateFolder(folderId: Int, title: String, iconName: String?, includedChatIds: List<Long>) =
        selectedContractsOrThrow().updateFolder(folderId, title, iconName, includedChatIds)
    override suspend fun reorderFolders(folderIds: List<Int>) = selectedContractsOrThrow().reorderFolders(folderIds)
    override suspend fun toggleMuteChats(chatIds: Set<Long>, mute: Boolean) = selectedContractsOrThrow().toggleMuteChats(chatIds, mute)
    override suspend fun toggleArchiveChats(chatIds: Set<Long>, archive: Boolean) = selectedContractsOrThrow().toggleArchiveChats(chatIds, archive)
    override suspend fun togglePinChats(chatIds: Set<Long>, pin: Boolean, folderId: Int) = selectedContractsOrThrow().togglePinChats(chatIds, pin, folderId)
    override suspend fun toggleReadChats(chatIds: Set<Long>, markAsUnread: Boolean) = selectedContractsOrThrow().toggleReadChats(chatIds, markAsUnread)
    override fun markChatsAsRead(chatIds: Set<Long>) = selectedContracts()?.markChatsAsRead(chatIds) ?: Unit
    override fun markFolderAsRead(folderId: Int, chatIds: Set<Long>) = selectedContracts()?.markFolderAsRead(folderId, chatIds) ?: Unit
    override suspend fun deleteChats(chatIds: Set<Long>) = selectedContractsOrThrow().deleteChats(chatIds)
    override suspend fun leaveChats(chatIds: Set<Long>) = selectedContractsOrThrow().leaveChats(chatIds)
    override suspend fun leaveChat(chatId: Long) = selectedContractsOrThrow().leaveChat(chatId)
    override fun setArchivePinned(pinned: Boolean) = selectedContracts()?.setArchivePinned(pinned) ?: Unit
    override suspend fun clearChatHistories(chatIds: Set<Long>, revoke: Boolean) = selectedContractsOrThrow().clearChatHistories(chatIds, revoke)
    override suspend fun clearChatHistory(chatId: Long, revoke: Boolean) = selectedContractsOrThrow().clearChatHistory(chatId, revoke)
    override suspend fun getChatLink(chatId: Long): String? = selectedContractsOrThrow().getChatLink(chatId)
    override suspend fun reportChats(chatIds: Set<Long>, reason: String, messageIds: List<Long>) = selectedContractsOrThrow().reportChats(chatIds, reason, messageIds)
    override suspend fun reportChat(chatId: Long, reason: String, messageIds: List<Long>) = selectedContractsOrThrow().reportChat(chatId, reason, messageIds)

    private fun <T> observe(source: (ChatReadContracts) -> Flow<T>): Flow<T> = selectedBackend.filterNotNull()
        .flatMapLatest { backend -> source(contractsFor(backend)) }

    private fun selectedContracts(): ChatReadContracts? = selectedBackend.value?.let(::contractsFor)
    private fun selectedContractsOrThrow(): ChatReadContracts = checkNotNull(selectedContracts()) { "Telegram backend selection is not loaded" }
    private fun contractsFor(backend: TelegramBackendKind): ChatReadContracts = when (backend) {
        TelegramBackendKind.LEGACY -> legacy
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto
    }

    internal interface ChatReadContracts : ChatListRepository, ChatFolderRepository, ChatOperationsRepository

    private companion object {
        const val DEFAULT_ACCOUNT_ID = "default"
    }
}
