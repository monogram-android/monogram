package org.monogram.data.mtproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.ChatType
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.DialogSnapshotModel
import org.monogram.domain.models.FolderModel
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.repository.ChatFolderRepository
import org.monogram.domain.repository.ChatListRepository
import org.monogram.domain.repository.ChatOperationsRepository
import org.monogram.domain.repository.ConnectionStatus
import org.monogram.domain.repository.DialogSnapshotRepository
import org.monogram.domain.repository.FolderChatsUpdate
import org.monogram.domain.repository.FolderLoadingUpdate
import org.monogram.domain.repository.MtProtoReadHistoryRepository
import org.monogram.data.backend.TelegramBackendChatReadRouter

/**
 * Read-only chat list backed by the persisted MTProto dialog projection.
 *
 * Commands and custom folders stay on their dedicated migration paths. This repository deliberately
 * exposes only the All chats folder until MTProto folder synchronization is implemented.
 */
internal class MtProtoDialogChatListRepository(
    private val dialogRepository: DialogSnapshotRepository,
    private val readHistoryRepository: MtProtoReadHistoryRepository,
    private val scope: CoroutineScope,
    private val archiveRepository: MtProtoArchiveRepository = MtProtoArchiveRepository { _, _ -> },
    private val dialogPinRepository: MtProtoDialogPinRepository = MtProtoDialogPinRepository { _, _ -> },
    private val muteRepository: MtProtoMuteRepository = MtProtoMuteRepository { _, _ -> },
    private val leaveChatRepository: MtProtoLeaveChatRepository = MtProtoLeaveChatRepository { },
    private val clearHistoryRepository: MtProtoClearHistoryRepository = MtProtoClearHistoryRepository { _, _ -> },
    private val deletePrivateDialogRepository: MtProtoDeletePrivateDialogRepository = MtProtoDeletePrivateDialogRepository { },
    private val reportPeerRepository: MtProtoReportPeerRepository = MtProtoReportPeerRepository { _, _, _ -> },
    private val dialogUnreadRepository: MtProtoDialogUnreadRepository = MtProtoDialogUnreadRepository { _, _ -> },
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : TelegramBackendChatReadRouter.ChatReadContracts {
    private val _chatListFlow = MutableStateFlow<List<ChatModel>>(emptyList())
    override val chatListFlow: StateFlow<List<ChatModel>> = _chatListFlow.asStateFlow()
    private val _isLoadingFlow = MutableStateFlow(false)
    override val isLoadingFlow: StateFlow<Boolean> = _isLoadingFlow.asStateFlow()
    private val _connectionStateFlow = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected)
    override val connectionStateFlow: StateFlow<ConnectionStatus> = _connectionStateFlow.asStateFlow()
    private val _folderChatsFlow = MutableSharedFlow<FolderChatsUpdate>(replay = 1, extraBufferCapacity = 1)
    override val folderChatsFlow: Flow<FolderChatsUpdate> = _folderChatsFlow.asSharedFlow()
    private val _foldersFlow = MutableStateFlow(listOf(ALL_CHATS_FOLDER))
    override val foldersFlow: StateFlow<List<FolderModel>> = _foldersFlow.asStateFlow()
    private val _folderLoadingFlow = MutableSharedFlow<FolderLoadingUpdate>(replay = 1, extraBufferCapacity = 1)
    override val folderLoadingFlow: Flow<FolderLoadingUpdate> = _folderLoadingFlow.asSharedFlow()
    override val isArchivePinned = MutableStateFlow(false).asStateFlow()
    override val isArchiveAlwaysVisible = MutableStateFlow(false).asStateFlow()

    init {
        refresh()
    }

    override fun loadNextChunk(limit: Int) = Unit

    override fun selectFolder(folderId: Int) {
        require(folderId == ALL_CHATS_FOLDER_ID) { "MTProto custom folders are not available" }
        scope.launch { publishCurrentChats() }
    }

    override fun refresh() {
        scope.launch {
            _isLoadingFlow.value = true
            _folderLoadingFlow.emit(FolderLoadingUpdate(ALL_CHATS_FOLDER_ID, true))
            runCatching { dialogRepository.getDialogs(accountId) }
                .onSuccess { dialogs ->
                    _chatListFlow.value = dialogs.mapNotNull(::toChatModel)
                        .sortedWith(compareByDescending<ChatModel> { it.lastMessageDate }.thenByDescending { it.lastMessageId })
                    publishCurrentChats()
                }
                .onFailure {
                    _connectionStateFlow.value = ConnectionStatus.Connecting
                }
            _isLoadingFlow.value = false
            _folderLoadingFlow.emit(FolderLoadingUpdate(ALL_CHATS_FOLDER_ID, false))
        }
    }

    override fun refreshOnResume() = refresh()

    override suspend fun getChatById(chatId: Long): ChatModel? = chatListFlow.value.firstOrNull { it.id == chatId }

    override suspend fun isChatArchived(chatId: Long): Boolean? =
        chatListFlow.value.firstOrNull { it.id == chatId }?.isArchived

    override fun retryConnection() = refresh()

    override suspend fun createFolder(title: String, iconName: String?, includedChatIds: List<Long>) = unsupportedFolders()

    override suspend fun deleteFolder(folderId: Int) = unsupportedFolders()

    override suspend fun updateFolder(folderId: Int, title: String, iconName: String?, includedChatIds: List<Long>) = unsupportedFolders()

    override suspend fun reorderFolders(folderIds: List<Int>) = unsupportedFolders()

    private suspend fun publishCurrentChats() {
        _folderChatsFlow.emit(FolderChatsUpdate(ALL_CHATS_FOLDER_ID, chatListFlow.value))
    }

    private fun toChatModel(dialog: DialogSnapshotModel): ChatModel? = with(dialog) {
        if (!isPeerResolved || isPeerDeleted || isPeerForbidden || peerType == DialogPeerType.UNKNOWN) return null
        val chatId = TelegramPeerChatId.encode(peerType, peerId)
        return ChatModel(
            id = chatId,
            title = title?.takeIf(String::isNotBlank) ?: username.orEmpty().ifBlank { chatId.toString() },
            unreadCount = unreadCount,
            lastMessageText = latestMessage.text.orEmpty(),
            lastMessageDate = latestMessage.date,
            lastMessageId = latestMessage.messageId,
            isLastMessageOutgoing = latestMessage.isOutgoing,
            messageSenderId = latestMessage.senderId,
            lastMessageContentType = if (latestMessage.hasMedia) "media" else "text",
            username = username,
            type = when (peerType) {
                DialogPeerType.PRIVATE -> ChatType.PRIVATE
                DialogPeerType.BASIC_GROUP -> ChatType.BASIC_GROUP
                DialogPeerType.SUPERGROUP,
                DialogPeerType.CHANNEL -> ChatType.SUPERGROUP
                DialogPeerType.UNKNOWN -> return null
            },
            isGroup = peerType != DialogPeerType.PRIVATE,
            isSupergroup = peerType == DialogPeerType.SUPERGROUP || peerType == DialogPeerType.CHANNEL,
            isChannel = peerType == DialogPeerType.CHANNEL,
            isMuted = isMuted,
        )
    }

    override suspend fun toggleMuteChats(chatIds: Set<Long>, mute: Boolean) {
        muteRepository.setMuted(chatIds, mute)
        refresh()
    }
    override suspend fun toggleArchiveChats(chatIds: Set<Long>, archive: Boolean) {
        archiveRepository.setArchived(chatIds, archive)
        refresh()
    }
    override suspend fun togglePinChats(chatIds: Set<Long>, pin: Boolean, folderId: Int) {
        require(folderId == ALL_CHATS_FOLDER_ID) { "MTProto folder-specific pinning is not available" }
        dialogPinRepository.setPinned(chatIds, pin)
        refresh()
    }

    override suspend fun toggleReadChats(chatIds: Set<Long>, markAsUnread: Boolean) {
        if (markAsUnread) dialogUnreadRepository.setUnread(chatIds, unread = true) else markChatsRead(chatIds)
    }

    override fun markChatsAsRead(chatIds: Set<Long>) {
        scope.launch { markChatsRead(chatIds) }
    }

    override fun markFolderAsRead(folderId: Int, chatIds: Set<Long>) {
        require(folderId == ALL_CHATS_FOLDER_ID) { "MTProto custom folders are not available" }
        scope.launch { markChatsRead(chatIds) }
    }
    override suspend fun deleteChats(chatIds: Set<Long>) {
        deletePrivateDialogRepository.delete(chatIds)
        refresh()
    }
    override suspend fun leaveChats(chatIds: Set<Long>) {
        leaveChatRepository.leave(chatIds)
        refresh()
    }
    override suspend fun leaveChat(chatId: Long) = leaveChats(setOf(chatId))
    override fun setArchivePinned(pinned: Boolean) = unsupportedOperations()
    override suspend fun clearChatHistories(chatIds: Set<Long>, revoke: Boolean) {
        clearHistoryRepository.clear(chatIds, revoke)
        refresh()
    }
    override suspend fun clearChatHistory(chatId: Long, revoke: Boolean) = clearChatHistories(setOf(chatId), revoke)
    override suspend fun getChatLink(chatId: Long): String? = unsupportedOperations()
    override suspend fun reportChats(chatIds: Set<Long>, reason: String, messageIds: List<Long>) {
        reportPeerRepository.report(chatIds, reason, messageIds)
    }
    override suspend fun reportChat(chatId: Long, reason: String, messageIds: List<Long>) =
        reportChats(setOf(chatId), reason, messageIds)

    private suspend fun markChatsRead(chatIds: Set<Long>) {
        chatListFlow.value
            .asSequence()
            .filter { it.id in chatIds && it.lastMessageId > 0L }
            .forEach { chat ->
                val peer = TelegramPeerChatId.decode(chat.id, chat.isChannel)
                readHistoryRepository.markRead(chat.id, peer.type, chat.lastMessageId)
            }
    }

    private fun unsupportedFolders(): Nothing =
        throw UnsupportedOperationException("MTProto custom folders are not available")

    private fun unsupportedOperations(): Nothing =
        throw UnsupportedOperationException("MTProto chat operations are not available")

    private companion object {
        const val DEFAULT_ACCOUNT_ID = "default"
        const val ALL_CHATS_FOLDER_ID = -1
        val ALL_CHATS_FOLDER = FolderModel(id = ALL_CHATS_FOLDER_ID, title = "All chats")
    }
}
