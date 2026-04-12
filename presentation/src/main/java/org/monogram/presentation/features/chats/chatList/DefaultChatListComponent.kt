package org.monogram.presentation.features.chats.chatList

import android.util.Log
import com.arkivanov.decompose.value.Value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.monogram.domain.models.BotMenuButtonModel
import org.monogram.domain.models.ChatType
import org.monogram.domain.models.UpdateState
import org.monogram.domain.repository.*
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.core.util.coRunCatching
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.features.chats.ChatListComponent
import org.monogram.presentation.root.AppComponentContext

class DefaultChatListComponent(
    context: AppComponentContext,
    private val onSelect: (Long, Long?) -> Unit,
    private val onProfileSelect: (Long) -> Unit,
    private val onSettingsClick: () -> Unit,
    private val onProxySettingsClick: () -> Unit,
    private val onConfirmForward: (Set<Long>) -> Unit = {},
    internal val isForwarding: Boolean = false,
    private val onNewChatClick: () -> Unit = {},
    private val onEditFoldersClick: () -> Unit = {},
    activeChatId: Value<Long>
) : ChatListComponent, AppComponentContext by context {

    private val chatListRepository: ChatListRepository = container.repositories.chatListRepository
    private val chatFolderRepository: ChatFolderRepository = container.repositories.chatFolderRepository
    private val chatSearchRepository: ChatSearchRepository = container.repositories.chatSearchRepository
    private val chatOperationsRepository: ChatOperationsRepository = container.repositories.chatOperationsRepository
    private val repositoryUser: UserRepository = container.repositories.userRepository
    private val userProfileEditRepository: UserProfileEditRepository = container.repositories.userProfileEditRepository
    private val botRepository: BotRepository = container.repositories.botRepository
    private val attachMenuBotRepository: AttachMenuBotRepository = container.repositories.attachMenuBotRepository
    private val updateRepository: UpdateRepository = container.repositories.updateRepository
    override val appPreferences: AppPreferences = container.preferences.appPreferences

    private val _uiState = MutableStateFlow(ChatListComponent.UiState(isForwarding = isForwarding))
    private val _foldersState = MutableStateFlow(ChatListComponent.FoldersState(isLoadingByFolder = mapOf(-1 to true)))
    private val _chatsState = MutableStateFlow(ChatListComponent.ChatsState())
    private val _selectionState = MutableStateFlow(ChatListComponent.SelectionState())
    private val _searchState = MutableStateFlow(ChatListComponent.SearchState())

    override val uiState: StateFlow<ChatListComponent.UiState> = _uiState.asStateFlow()
    override val foldersState: StateFlow<ChatListComponent.FoldersState> = _foldersState.asStateFlow()
    override val chatsState: StateFlow<ChatListComponent.ChatsState> = _chatsState
    override val selectionState: StateFlow<ChatListComponent.SelectionState> = _selectionState.asStateFlow()
    override val searchState: StateFlow<ChatListComponent.SearchState> = _searchState.asStateFlow()

    private val scope = componentScope
    private var searchJob: Job? = null
    private var isFetchingMoreMessages = false
    private var nextMessagesOffset = ""

    init {
        activeChatId.subscribe { id ->
            _selectionState.update { it.copy(activeChatId = id) }
        }

        repositoryUser.currentUserFlow
            .onEach { user ->
                if (user != null) {
                    _uiState.update { it.copy(currentUser = user) }
                }
            }
            .launchIn(scope)

        scope.launch {
            repositoryUser.getMe()
        }

        chatFolderRepository.folderChatsFlow
            .onEach { update ->
                val distinctList = update.chats.distinctBy { it.id }
                val pinnedCount = distinctList.count { it.isPinned }
                if (pinnedCount > 0) {
                    Log.d(
                        TAG,
                        "folder=${update.folderId} chats=${distinctList.size} pinned=$pinnedCount pinnedIds=${
                            distinctList.filter { it.isPinned }.take(10).joinToString { it.id.toString() }
                        }"
                    )
                }
                _foldersState.update {
                    val newChatsByFolder = it.chatsByFolder.toMutableMap()
                    newChatsByFolder[update.folderId] = distinctList
                    it.copy(chatsByFolder = newChatsByFolder)
                }

                if (update.folderId == _foldersState.value.selectedFolderId) {
                    _chatsState.update {
                        it.copy(chats = distinctList)
                    }
                }
            }
            .launchIn(scope)

        chatFolderRepository.foldersFlow
            .onEach { folders ->
                _foldersState.update { it.copy(folders = folders) }
            }
            .launchIn(scope)

        chatFolderRepository.folderLoadingFlow
            .onEach { update ->
                _foldersState.update {
                    val newLoadingByFolder = it.isLoadingByFolder.toMutableMap()
                    newLoadingByFolder[update.folderId] = update.isLoading
                    it.copy(isLoadingByFolder = newLoadingByFolder)
                }
                if (update.folderId == _foldersState.value.selectedFolderId) {
                    _chatsState.update {
                        it.copy(isLoading = update.isLoading)
                    }
                }
            }
            .launchIn(scope)

        chatListRepository.connectionStateFlow
            .onEach { status ->
                _uiState.update { it.copy(connectionStatus = status) }
            }
            .launchIn(scope)

        appPreferences.enabledProxyId
            .onEach { enabledProxyId ->
                _uiState.update { it.copy(isProxyEnabled = enabledProxyId != null) }
            }
            .launchIn(scope)

        chatOperationsRepository.isArchivePinned
            .onEach { isPinned ->
                _uiState.update { it.copy(isArchivePinned = isPinned) }
            }
            .launchIn(scope)

        chatOperationsRepository.isArchiveAlwaysVisible
            .onEach { alwaysVisible ->
                _uiState.update { it.copy(isArchiveAlwaysVisible = alwaysVisible) }
            }
            .launchIn(scope)

        chatSearchRepository.searchHistory
            .onEach { history ->
                _searchState.update {
                    it.copy(
                        recentUsers = history.filter { chat ->
                            (chat.type == ChatType.PRIVATE || chat.type == ChatType.SECRET) && !chat.isBot
                        },
                        recentOthers = history.filter { chat ->
                            chat.type != ChatType.PRIVATE && chat.type != ChatType.SECRET || chat.isBot
                        }
                    )
                }
            }
            .launchIn(scope)

        attachMenuBotRepository.getAttachMenuBots()
            .onEach { bots ->
                _uiState.update { it.copy(attachMenuBots = bots) }

                bots.firstOrNull()?.let { bot ->
                    if (bot.botUserId != 0L) {
                        val botInfo = botRepository.getBotInfo(bot.botUserId)
                        val menuButton = botInfo?.menuButton
                        if (menuButton is BotMenuButtonModel.WebApp) {
                            _uiState.update {
                                it.copy(
                                    botWebAppUrl = menuButton.url,
                                    botWebAppName = menuButton.text
                                )
                            }
                        }
                    }
                }
            }
            .launchIn(scope)

        updateRepository.updateState
            .onEach { updateState ->
                _uiState.update { it.copy(updateState = updateState) }
            }
            .launchIn(scope)

        scope.launch {
            updateRepository.checkForUpdates()
        }

        scope.launch(Dispatchers.IO) {
            chatListRepository.selectFolder(_foldersState.value.selectedFolderId)
        }
    }

    override fun retryConnection() {
        chatListRepository.retryConnection()
    }

    override fun onFolderClicked(id: Int) {
        if (_foldersState.value.selectedFolderId == id) return

        _foldersState.update {
            val loadingByFolder = it.isLoadingByFolder.toMutableMap()
            loadingByFolder[id] = true
            it.copy(
                selectedFolderId = id,
                isLoadingByFolder = loadingByFolder
            )
        }

        _chatsState.value = ChatListComponent.ChatsState(
            chats = _foldersState.value.chatsByFolder[id].orEmpty(),
            isLoading = true
        )

        scope.launch(Dispatchers.IO) {
            chatListRepository.selectFolder(id)
        }
    }

    override fun loadMore(folderId: Int?) {
        val targetFolderId = folderId ?: _foldersState.value.selectedFolderId
        if (_foldersState.value.isLoadingByFolder[targetFolderId] == true) return

        scope.launch(Dispatchers.IO) {
            if (folderId != null && folderId != _foldersState.value.selectedFolderId) {
                return@launch
            }
            chatListRepository.loadNextChunk(20)
        }
    }

    override fun loadMoreMessages() {
        if (isFetchingMoreMessages || nextMessagesOffset.isEmpty()) return

        isFetchingMoreMessages = true
        val query = _searchState.value.searchQuery
        scope.launch(Dispatchers.IO) {
            val result = chatSearchRepository.searchMessages(query, offset = nextMessagesOffset)
            nextMessagesOffset = result.nextOffset
            _searchState.update {
                it.copy(
                    messageSearchResults = it.messageSearchResults + result.messages,
                    canLoadMoreMessages = nextMessagesOffset.isNotEmpty()
                )
            }
            isFetchingMoreMessages = false
        }
    }

    override fun onChatClicked(id: Long) {
        if (_uiState.value.isForwarding) {
            toggleSelection(id)
        } else if (_selectionState.value.selectedChatIds.isNotEmpty()) {
            toggleSelection(id)
        } else {
            if (_searchState.value.isSearchActive) {
                chatSearchRepository.addSearchChatId(id)
            }
            onSelect(id, null)
        }
    }

    override fun onProfileClicked(id: Long) {
        onProfileSelect(id)
    }

    override fun onMessageClicked(chatId: Long, messageId: Long) {
        if (_uiState.value.isForwarding) {
            toggleSelection(chatId)
        } else if (_selectionState.value.selectedChatIds.isNotEmpty()) {
            toggleSelection(chatId)
        } else {
            if (_searchState.value.isSearchActive) {
                chatSearchRepository.addSearchChatId(chatId)
            }
            onSelect(chatId, messageId)
        }
    }

    override fun onChatLongClicked(id: Long) {
        toggleSelection(id)
    }

    override fun clearSelection() {
        _selectionState.update { it.copy(selectedChatIds = emptySet()) }
    }

    override fun onSettingsClicked() {
        onSettingsClick()
    }

    override fun onSearchToggle() {
        val isSearchActive = !_searchState.value.isSearchActive
        _searchState.update {
            it.copy(
                isSearchActive = isSearchActive,
                searchQuery = "",
                searchResults = emptyList(),
                globalSearchResults = emptyList(),
                messageSearchResults = emptyList(),
                canLoadMoreMessages = false
            )
        }
        nextMessagesOffset = ""
    }

    override fun onSearchQueryChange(query: String) {
        _searchState.update { it.copy(searchQuery = query) }

        searchJob?.cancel()
        searchJob = scope.launch(Dispatchers.IO) {
            delay(300)
            if (query.isNotEmpty()) {
                if (_foldersState.value.selectedFolderId == -2) {
                    val archivedChats = _foldersState.value.chatsByFolder[-2].orEmpty()
                    val trimmedQuery = query.trim()
                    val archiveResults = archivedChats.filter { chat ->
                        chat.title.contains(trimmedQuery, ignoreCase = true) ||
                                chat.lastMessageText.contains(trimmedQuery, ignoreCase = true)
                    }

                    _searchState.update {
                        it.copy(
                            searchQuery = query,
                            searchResults = archiveResults,
                            globalSearchResults = emptyList(),
                            messageSearchResults = emptyList(),
                            canLoadMoreMessages = false
                        )
                    }
                    nextMessagesOffset = ""
                    return@launch
                }

                val localResults = chatSearchRepository.searchChats(query)
                _searchState.update { it.copy(searchResults = localResults) }

                val globalResults = chatSearchRepository.searchPublicChats(query)
                _searchState.update { it.copy(globalSearchResults = globalResults) }

                val messageResults = chatSearchRepository.searchMessages(query)
                nextMessagesOffset = messageResults.nextOffset
                _searchState.update {
                    it.copy(
                        messageSearchResults = messageResults.messages,
                        canLoadMoreMessages = nextMessagesOffset.isNotEmpty()
                    )
                }
            } else {
                nextMessagesOffset = ""
                _searchState.update {
                    it.copy(
                        searchQuery = "",
                        searchResults = emptyList(),
                        globalSearchResults = emptyList(),
                        messageSearchResults = emptyList(),
                        canLoadMoreMessages = false
                    )
                }
            }
        }
    }

    override fun onSetEmojiStatus(customEmojiId: Long, statusPath: String?) {
        _uiState.update { state ->
            val user = state.currentUser ?: return@update state
            state.copy(
                currentUser = user.copy(
                    statusEmojiId = customEmojiId,
                    statusEmojiPath = statusPath ?: user.statusEmojiPath
                )
            )
        }

        scope.launch(Dispatchers.IO) {
            coRunCatching {
                userProfileEditRepository.setEmojiStatus(customEmojiId)
            }
        }
    }

    override fun onClearSearchHistory() {
        chatSearchRepository.clearSearchHistory()
    }

    override fun onRemoveSearchHistoryItem(chatId: Long) {
        chatSearchRepository.removeSearchChatId(chatId)
    }

    override fun onMuteSelected(mute: Boolean) {
        val selectedIds = _selectionState.value.selectedChatIds
        val selectedChats = _chatsState.value.chats.filter { selectedIds.contains(it.id) }
        val shouldMute = selectedChats.any { !it.isMuted }

        scope.launch(Dispatchers.IO) {
            chatOperationsRepository.toggleMuteChats(selectedIds, shouldMute)
            clearSelection()
        }
    }

    override fun onArchiveSelected(archive: Boolean) {
        val selectedIds = _selectionState.value.selectedChatIds
        scope.launch(Dispatchers.IO) {
            chatOperationsRepository.toggleArchiveChats(selectedIds, archive)
            clearSelection()
        }
    }

    override fun onPinSelected() {
        val selectedIds = _selectionState.value.selectedChatIds
        val selectedChats = _chatsState.value.chats.filter { selectedIds.contains(it.id) }
        val shouldPin = selectedChats.any { !it.isPinned }
        val folderId = _foldersState.value.selectedFolderId

        scope.launch(Dispatchers.IO) {
            chatOperationsRepository.togglePinChats(selectedIds, shouldPin, folderId)
            clearSelection()
        }
    }

    override fun onToggleReadSelected() {
        val selectedIds = _selectionState.value.selectedChatIds
        val selectedChats = _chatsState.value.chats.filter { selectedIds.contains(it.id) }
        val shouldMarkUnread = selectedChats.any { !it.isMarkedAsUnread }

        scope.launch(Dispatchers.IO) {
            chatOperationsRepository.toggleReadChats(selectedIds, shouldMarkUnread)
            clearSelection()
        }
    }

    override fun onDeleteSelected() {
        val selectedIds = _selectionState.value.selectedChatIds
        scope.launch(Dispatchers.IO) {
            chatOperationsRepository.deleteChats(selectedIds)
            clearSelection()
        }
    }

    override fun onArchivePinToggle() {
        chatOperationsRepository.setArchivePinned(!_uiState.value.isArchivePinned)
    }

    override fun onConfirmForwarding() {
        val selectedChatIds = _selectionState.value.selectedChatIds
        if (selectedChatIds.isNotEmpty()) {
            onConfirmForward(selectedChatIds)
        }
    }

    override fun onNewChatClicked() {
        onNewChatClick()
    }

    override fun onProxySettingsClicked() {
        onProxySettingsClick()
    }

    override fun onEditFoldersClicked() {
        onEditFoldersClick()
    }

    override fun onDeleteFolder(folderId: Int) {
        if (folderId <= 0) return

        scope.launch(Dispatchers.IO) {
            chatFolderRepository.deleteFolder(folderId)
            if (_foldersState.value.selectedFolderId == folderId) {
                onFolderClicked(-1)
            }
        }
    }

    override fun onEditFolder(folderId: Int) {
        if (folderId <= 0) return
        onEditFoldersClick()
    }

    override fun onOpenInstantView(url: String) {
        _uiState.update { it.copy(instantViewUrl = url) }
    }

    override fun onDismissInstantView() {
        _uiState.update { it.copy(instantViewUrl = null) }
    }

    override fun onOpenWebApp(url: String, botUserId: Long, botName: String) {
        _uiState.update {
            it.copy(
                webAppUrl = url,
                webAppBotId = botUserId,
                webAppBotName = botName
            )
        }
    }

    override fun onDismissWebApp() {
        _uiState.update {
            it.copy(
                webAppUrl = null,
                webAppBotId = null,
                webAppBotName = null
            )
        }
    }

    override fun onOpenWebView(url: String) {
        _uiState.update { it.copy(webViewUrl = url) }
    }

    override fun onDismissWebView() {
        _uiState.update { it.copy(webViewUrl = null) }
    }

    override fun onUpdateClicked() {
        val currentState = _uiState.value.updateState
        when (currentState) {
            is UpdateState.UpdateAvailable -> {
                updateRepository.downloadUpdate()
            }

            is UpdateState.ReadyToInstall -> {
                updateRepository.installUpdate()
            }

            is UpdateState.Downloading -> {
                updateRepository.cancelDownload()
            }

            else -> {
                onSettingsClick()
            }
        }
    }

    override fun handleBack(): Boolean {
        return when {
            uiState.value.webViewUrl != null -> {
                onDismissWebView()
                true
            }
            uiState.value.webAppUrl != null -> {
                onDismissWebApp()
                true
            }
            uiState.value.instantViewUrl != null -> {
                onDismissInstantView()
                true
            }
            searchState.value.isSearchActive -> {
                onSearchToggle()
                true
            }
            selectionState.value.selectedChatIds.isNotEmpty() -> {
                clearSelection()
                true
            }
            foldersState.value.selectedFolderId == -2 -> {
                onFolderClicked(-1)
                true
            }

            uiState.value.isForwarding -> {
                onSelect(0L, null)
                true
            }
            else -> false
        }
    }

    override fun updateScrollPosition(folderId: Int, index: Int, offset: Int) {
        _foldersState.update {
            val newPositions = it.scrollPositions.toMutableMap()
            newPositions[folderId] = index to offset
            it.copy(scrollPositions = newPositions)
        }
    }

    companion object {
        private const val TAG = "PinnedUiDiag"
    }

    private fun toggleSelection(id: Long) {
        val currentSelection = _selectionState.value.selectedChatIds
        val newSelection = if (currentSelection.contains(id)) {
            currentSelection - id
        } else {
            currentSelection + id
        }
        _selectionState.value = _selectionState.value.copy(selectedChatIds = newSelection)
    }
}
