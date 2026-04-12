package org.monogram.presentation.features.chats.chatList

import android.util.Log
import com.arkivanov.decompose.value.Value
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.monogram.presentation.features.chats.ChatListStore
import org.monogram.presentation.features.chats.ChatListStoreFactory
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

    internal val _state = MutableStateFlow(
        ChatListComponent.State(
            isForwarding = isForwarding,
            isLoadingByFolder = mapOf(-1 to true)
        )
    )
    private val _uiState = MutableStateFlow(_state.value.toUiState())
    private val _foldersState = MutableStateFlow(_state.value.toFoldersState())
    private val _chatsState = MutableStateFlow(_state.value.toChatsState())
    private val _selectionState = MutableStateFlow(_state.value.toSelectionState())
    private val _searchState = MutableStateFlow(_state.value.toSearchState())

    private val store = instanceKeeper.getStore {
        ChatListStoreFactory(
            storeFactory = DefaultStoreFactory(),
            component = this
        ).create()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val state: StateFlow<ChatListComponent.State> = store.stateFlow
    override val uiState: StateFlow<ChatListComponent.UiState> = _uiState.asStateFlow()
    override val foldersState: StateFlow<ChatListComponent.FoldersState> = _foldersState.asStateFlow()
    override val chatsState: StateFlow<ChatListComponent.ChatsState> = _chatsState.asStateFlow()
    override val selectionState: StateFlow<ChatListComponent.SelectionState> = _selectionState.asStateFlow()
    override val searchState: StateFlow<ChatListComponent.SearchState> = _searchState.asStateFlow()

    private val scope = componentScope
    private var searchJob: Job? = null
    private var isFetchingMoreMessages = false
    private var nextMessagesOffset = ""

    private fun ChatListComponent.State.toUiState() = ChatListComponent.UiState(
        currentUser = currentUser,
        connectionStatus = connectionStatus,
        isArchivePinned = isArchivePinned,
        isArchiveAlwaysVisible = isArchiveAlwaysVisible,
        isForwarding = isForwarding,
        instantViewUrl = instantViewUrl,
        isProxyEnabled = isProxyEnabled,
        attachMenuBots = attachMenuBots,
        botWebAppUrl = botWebAppUrl,
        botWebAppName = botWebAppName,
        webAppUrl = webAppUrl,
        webAppBotId = webAppBotId,
        webAppBotName = webAppBotName,
        webViewUrl = webViewUrl,
        updateState = updateState
    )

    private fun ChatListComponent.State.toFoldersState() = ChatListComponent.FoldersState(
        chatsByFolder = chatsByFolder,
        folders = folders,
        selectedFolderId = selectedFolderId,
        isLoadingByFolder = isLoadingByFolder,
        scrollPositions = scrollPositions
    )

    private fun ChatListComponent.State.toChatsState() = ChatListComponent.ChatsState(
        chats = chats,
        isLoading = isLoading
    )

    private fun ChatListComponent.State.toSelectionState() = ChatListComponent.SelectionState(
        selectedChatIds = selectedChatIds,
        activeChatId = activeChatId
    )

    private fun ChatListComponent.State.toSearchState() = ChatListComponent.SearchState(
        isSearchActive = isSearchActive,
        searchQuery = searchQuery,
        searchResults = searchResults,
        globalSearchResults = globalSearchResults,
        messageSearchResults = messageSearchResults,
        recentUsers = recentUsers,
        recentOthers = recentOthers,
        canLoadMoreMessages = canLoadMoreMessages
    )

    init {
        activeChatId.subscribe { id ->
            _state.update { it.copy(activeChatId = id) }
        }

        repositoryUser.currentUserFlow
            .onEach { user ->
                if (user != null) {
                    _state.update { it.copy(currentUser = user) }
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
                _state.update {
                    val newChatsByFolder = it.chatsByFolder.toMutableMap()
                    newChatsByFolder[update.folderId] = distinctList
                    it.copy(chatsByFolder = newChatsByFolder)
                }
            }
            .launchIn(scope)

        chatFolderRepository.foldersFlow
            .onEach { folders ->
                _state.update { it.copy(folders = folders) }
            }
            .launchIn(scope)

        chatFolderRepository.folderLoadingFlow
            .onEach { update ->
                _state.update {
                    val newLoadingByFolder = it.isLoadingByFolder.toMutableMap()
                    newLoadingByFolder[update.folderId] = update.isLoading
                    it.copy(isLoadingByFolder = newLoadingByFolder)
                }
            }
            .launchIn(scope)

        chatListRepository.connectionStateFlow
            .onEach { status ->
                _state.update { it.copy(connectionStatus = status) }
            }
            .launchIn(scope)

        appPreferences.enabledProxyId
            .onEach { enabledProxyId ->
                _state.update { it.copy(isProxyEnabled = enabledProxyId != null) }
            }
            .launchIn(scope)

        chatOperationsRepository.isArchivePinned
            .onEach { isPinned ->
                _state.update { it.copy(isArchivePinned = isPinned) }
            }
            .launchIn(scope)

        chatOperationsRepository.isArchiveAlwaysVisible
            .onEach { alwaysVisible ->
                _state.update { it.copy(isArchiveAlwaysVisible = alwaysVisible) }
            }
            .launchIn(scope)

        chatSearchRepository.searchHistory
            .onEach { history ->
                _state.update {
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
                _state.update { it.copy(attachMenuBots = bots) }

                bots.firstOrNull()?.let { bot ->
                    if (bot.botUserId != 0L) {
                        val botInfo = botRepository.getBotInfo(bot.botUserId)
                        val menuButton = botInfo?.menuButton
                        if (menuButton is BotMenuButtonModel.WebApp) {
                            _state.update {
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
                _state.update { it.copy(updateState = updateState) }
            }
            .launchIn(scope)

        scope.launch {
            updateRepository.checkForUpdates()
        }

        _state.onEach {
            _uiState.value = it.toUiState()
            _foldersState.value = it.toFoldersState()
            _chatsState.value = it.toChatsState()
            _selectionState.value = it.toSelectionState()
            _searchState.value = it.toSearchState()
            store.accept(ChatListStore.Intent.UpdateState(it))
        }.launchIn(scope)

        store.labels
            .onEach { label ->
                when (label) {
                    is ChatListStore.Label.OpenChat -> onSelect(label.chatId, label.messageId)
                    is ChatListStore.Label.OpenProfile -> onProfileSelect(label.id)
                    ChatListStore.Label.OpenSettings -> onSettingsClick()
                    ChatListStore.Label.OpenProxySettings -> onProxySettingsClick()
                    ChatListStore.Label.OpenNewChat -> onNewChatClick()
                    is ChatListStore.Label.ConfirmForward -> onConfirmForward(label.selectedChatIds)
                    is ChatListStore.Label.EditFolders -> onEditFoldersClick()
                }
            }
            .launchIn(scope)

        scope.launch(Dispatchers.IO) {
            chatListRepository.selectFolder(_state.value.selectedFolderId)
        }
    }

    override fun retryConnection() = store.accept(ChatListStore.Intent.RetryConnection)

    internal fun handleRetryConnection() {
        chatListRepository.retryConnection()
    }

    override fun onFolderClicked(id: Int) = store.accept(ChatListStore.Intent.FolderClicked(id))

    internal fun handleFolderClicked(id: Int) {
        if (_state.value.selectedFolderId == id) return

        _state.update {
            val loadingByFolder = it.isLoadingByFolder.toMutableMap()
            loadingByFolder[id] = true
            it.copy(
                selectedFolderId = id,
                isLoadingByFolder = loadingByFolder
            )
        }

        scope.launch(Dispatchers.IO) {
            chatListRepository.selectFolder(id)
        }
    }

    override fun loadMore(folderId: Int?) = store.accept(ChatListStore.Intent.LoadMore(folderId))

    internal fun handleLoadMore(folderId: Int?) {
        val targetFolderId = folderId ?: _state.value.selectedFolderId
        if (_state.value.isLoadingByFolder[targetFolderId] == true) return

        scope.launch(Dispatchers.IO) {
            if (folderId != null && folderId != _state.value.selectedFolderId) {
                return@launch
            }
            chatListRepository.loadNextChunk(20)
        }
    }

    override fun loadMoreMessages() = store.accept(ChatListStore.Intent.LoadMoreMessages)

    internal fun handleLoadMoreMessages() {
        if (isFetchingMoreMessages || nextMessagesOffset.isEmpty()) return

        isFetchingMoreMessages = true
        val query = _state.value.searchQuery
        scope.launch(Dispatchers.IO) {
            val result = chatSearchRepository.searchMessages(query, offset = nextMessagesOffset)
            nextMessagesOffset = result.nextOffset
            _state.update {
                it.copy(
                    messageSearchResults = it.messageSearchResults + result.messages,
                    canLoadMoreMessages = nextMessagesOffset.isNotEmpty()
                )
            }
            isFetchingMoreMessages = false
        }
    }

    override fun onChatClicked(id: Long) = store.accept(ChatListStore.Intent.ChatClicked(id))

    internal fun handleChatClicked(id: Long): ChatListStore.Label.OpenChat? {
        if (_state.value.isForwarding) {
            toggleSelection(id)
            return null
        } else if (_state.value.selectedChatIds.isNotEmpty()) {
            toggleSelection(id)
            return null
        } else {
            if (_state.value.isSearchActive) {
                chatSearchRepository.addSearchChatId(id)
            }
            return ChatListStore.Label.OpenChat(id, null)
        }
    }

    override fun onProfileClicked(id: Long) = store.accept(ChatListStore.Intent.ProfileClicked(id))

    internal fun handleMessageClicked(chatId: Long, messageId: Long): ChatListStore.Label.OpenChat? {
        if (_state.value.isForwarding) {
            toggleSelection(chatId)
            return null
        } else if (_state.value.selectedChatIds.isNotEmpty()) {
            toggleSelection(chatId)
            return null
        } else {
            if (_state.value.isSearchActive) {
                chatSearchRepository.addSearchChatId(chatId)
            }
            return ChatListStore.Label.OpenChat(chatId, messageId)
        }
    }

    override fun onMessageClicked(chatId: Long, messageId: Long) =
        store.accept(ChatListStore.Intent.MessageClicked(chatId, messageId))

    override fun onChatLongClicked(id: Long) = store.accept(ChatListStore.Intent.ChatLongClicked(id))

    internal fun handleChatLongClicked(id: Long) {
        toggleSelection(id)
    }

    override fun clearSelection() = store.accept(ChatListStore.Intent.ClearSelection)

    internal fun handleClearSelection() {
        _state.update { it.copy(selectedChatIds = emptySet()) }
    }

    override fun onSettingsClicked() = store.accept(ChatListStore.Intent.SettingsClicked)

    override fun onSearchToggle() = store.accept(ChatListStore.Intent.SearchToggle)

    internal fun handleSearchToggle() {
        val isSearchActive = !_state.value.isSearchActive
        _state.update {
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

    override fun onSearchQueryChange(query: String) = store.accept(ChatListStore.Intent.SearchQueryChange(query))

    internal fun handleSearchQueryChange(query: String) {
        _state.update { it.copy(searchQuery = query) }

        searchJob?.cancel()
        searchJob = scope.launch(Dispatchers.IO) {
            delay(300)
            if (query.isNotEmpty()) {
                if (_state.value.selectedFolderId == -2) {
                    val archivedChats = _state.value.chatsByFolder[-2].orEmpty()
                    val trimmedQuery = query.trim()
                    val archiveResults = archivedChats.filter { chat ->
                        chat.title.contains(trimmedQuery, ignoreCase = true) ||
                                chat.lastMessageText.contains(trimmedQuery, ignoreCase = true)
                    }

                    _state.update {
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
                _state.update { it.copy(searchResults = localResults) }

                val globalResults = chatSearchRepository.searchPublicChats(query)
                _state.update { it.copy(globalSearchResults = globalResults) }

                val messageResults = chatSearchRepository.searchMessages(query)
                nextMessagesOffset = messageResults.nextOffset
                _state.update {
                    it.copy(
                        messageSearchResults = messageResults.messages,
                        canLoadMoreMessages = nextMessagesOffset.isNotEmpty()
                    )
                }
            } else {
                nextMessagesOffset = ""
                _state.update {
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

    override fun onSetEmojiStatus(customEmojiId: Long, statusPath: String?) =
        store.accept(ChatListStore.Intent.SetEmojiStatus(customEmojiId, statusPath))

    internal fun handleSetEmojiStatus(customEmojiId: Long, statusPath: String?) {
        _state.update { state ->
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

    override fun onClearSearchHistory() = store.accept(ChatListStore.Intent.ClearSearchHistory)

    internal fun handleClearSearchHistory() {
        chatSearchRepository.clearSearchHistory()
    }

    override fun onRemoveSearchHistoryItem(chatId: Long) =
        store.accept(ChatListStore.Intent.RemoveSearchHistoryItem(chatId))

    internal fun handleRemoveSearchHistoryItem(chatId: Long) {
        chatSearchRepository.removeSearchChatId(chatId)
    }

    override fun onMuteSelected(mute: Boolean) = store.accept(ChatListStore.Intent.MuteSelected(mute))

    internal fun handleMuteSelected(mute: Boolean) {
        val selectedIds = _state.value.selectedChatIds
        val selectedChats = _state.value.chats.filter { selectedIds.contains(it.id) }
        val shouldMute = selectedChats.any { !it.isMuted }

        scope.launch(Dispatchers.IO) {
            chatOperationsRepository.toggleMuteChats(selectedIds, shouldMute)
            clearSelection()
        }
    }

    override fun onArchiveSelected(archive: Boolean) = store.accept(ChatListStore.Intent.ArchiveSelected(archive))

    internal fun handleArchiveSelected(archive: Boolean) {
        val selectedIds = _state.value.selectedChatIds
        scope.launch(Dispatchers.IO) {
            chatOperationsRepository.toggleArchiveChats(selectedIds, archive)
            handleClearSelection()
        }
    }

    override fun onPinSelected() = store.accept(ChatListStore.Intent.PinSelected)

    internal fun handlePinSelected() {
        val selectedIds = _state.value.selectedChatIds
        val selectedChats = _state.value.chats.filter { selectedIds.contains(it.id) }
        val shouldPin = selectedChats.any { !it.isPinned }
        val folderId = _state.value.selectedFolderId

        scope.launch(Dispatchers.IO) {
            chatOperationsRepository.togglePinChats(selectedIds, shouldPin, folderId)
            handleClearSelection()
        }
    }

    override fun onToggleReadSelected() = store.accept(ChatListStore.Intent.ToggleReadSelected)

    internal fun handleToggleReadSelected() {
        val selectedIds = _state.value.selectedChatIds
        val selectedChats = _state.value.chats.filter { selectedIds.contains(it.id) }
        val shouldMarkUnread = selectedChats.any { !it.isMarkedAsUnread }

        scope.launch(Dispatchers.IO) {
            chatOperationsRepository.toggleReadChats(selectedIds, shouldMarkUnread)
            handleClearSelection()
        }
    }

    override fun onDeleteSelected() = store.accept(ChatListStore.Intent.DeleteSelected)

    internal fun handleDeleteSelected() {
        val selectedIds = _state.value.selectedChatIds
        scope.launch(Dispatchers.IO) {
            chatOperationsRepository.deleteChats(selectedIds)
            handleClearSelection()
        }
    }

    override fun onArchivePinToggle() = store.accept(ChatListStore.Intent.ArchivePinToggle)

    internal fun handleArchivePinToggle() {
        chatOperationsRepository.setArchivePinned(!_state.value.isArchivePinned)
    }

    override fun onConfirmForwarding() = store.accept(ChatListStore.Intent.ConfirmForwarding)

    internal fun handleConfirmForwarding(): ChatListStore.Label.ConfirmForward? {
        val selectedChatIds = _state.value.selectedChatIds
        return selectedChatIds.takeIf { it.isNotEmpty() }?.let(ChatListStore.Label::ConfirmForward)
    }

    override fun onNewChatClicked() = store.accept(ChatListStore.Intent.NewChatClicked)

    override fun onProxySettingsClicked() = store.accept(ChatListStore.Intent.ProxySettingsClicked)

    override fun onEditFoldersClicked() = store.accept(ChatListStore.Intent.EditFoldersClicked)

    override fun onDeleteFolder(folderId: Int) = store.accept(ChatListStore.Intent.DeleteFolder(folderId))

    internal fun handleDeleteFolder(folderId: Int) {
        if (folderId <= 0) return

        scope.launch(Dispatchers.IO) {
            chatFolderRepository.deleteFolder(folderId)
            if (_state.value.selectedFolderId == folderId) {
                handleFolderClicked(-1)
            }
        }
    }

    override fun onEditFolder(folderId: Int) = store.accept(ChatListStore.Intent.EditFolder(folderId))

    internal fun handleEditFolder(folderId: Int): ChatListStore.Label.EditFolders? {
        if (folderId <= 0) return null
        return ChatListStore.Label.EditFolders(folderId)
    }

    override fun onOpenInstantView(url: String) = store.accept(ChatListStore.Intent.OpenInstantView(url))

    internal fun handleOpenInstantView(url: String) {
        _state.update { it.copy(instantViewUrl = url) }
    }

    override fun onDismissInstantView() = store.accept(ChatListStore.Intent.DismissInstantView)

    internal fun handleDismissInstantView() {
        _state.update { it.copy(instantViewUrl = null) }
    }

    override fun onOpenWebApp(url: String, botUserId: Long, botName: String) =
        store.accept(ChatListStore.Intent.OpenWebApp(url, botUserId, botName))

    internal fun handleOpenWebApp(url: String, botUserId: Long, botName: String) {
        _state.update {
            it.copy(
                webAppUrl = url,
                webAppBotId = botUserId,
                webAppBotName = botName
            )
        }
    }

    override fun onDismissWebApp() = store.accept(ChatListStore.Intent.DismissWebApp)

    internal fun handleDismissWebApp() {
        _state.update {
            it.copy(
                webAppUrl = null,
                webAppBotId = null,
                webAppBotName = null
            )
        }
    }

    override fun onOpenWebView(url: String) = store.accept(ChatListStore.Intent.OpenWebView(url))

    internal fun handleOpenWebView(url: String) {
        _state.update { it.copy(webViewUrl = url) }
    }

    override fun onDismissWebView() = store.accept(ChatListStore.Intent.DismissWebView)

    internal fun handleDismissWebView() {
        _state.update { it.copy(webViewUrl = null) }
    }

    override fun onUpdateClicked() = store.accept(ChatListStore.Intent.UpdateClicked)

    internal fun handleUpdateClicked(): ChatListStore.Label.OpenSettings? {
        val currentState = _state.value.updateState
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
                return ChatListStore.Label.OpenSettings
            }
        }
        return null
    }

    override fun handleBack(): Boolean {
        return when {
            state.value.webViewUrl != null -> {
                handleDismissWebView()
                true
            }
            state.value.webAppUrl != null -> {
                handleDismissWebApp()
                true
            }
            state.value.instantViewUrl != null -> {
                handleDismissInstantView()
                true
            }
            state.value.isSearchActive -> {
                handleSearchToggle()
                true
            }
            state.value.selectedChatIds.isNotEmpty() -> {
                handleClearSelection()
                true
            }
            state.value.selectedFolderId == -2 -> {
                handleFolderClicked(-1)
                true
            }

            state.value.isForwarding -> {
                onSelect(0L, null)
                true
            }
            else -> false
        }
    }

    override fun updateScrollPosition(folderId: Int, index: Int, offset: Int) =
        store.accept(ChatListStore.Intent.UpdateScrollPosition(folderId, index, offset))

    internal fun handleUpdateScrollPosition(folderId: Int, index: Int, offset: Int) {
        _state.update {
            val newPositions = it.scrollPositions.toMutableMap()
            newPositions[folderId] = index to offset
            it.copy(scrollPositions = newPositions)
        }
    }

    companion object {
        private const val TAG = "PinnedUiDiag"
    }

    private fun toggleSelection(id: Long) {
        val currentSelection = _state.value.selectedChatIds
        val newSelection = if (currentSelection.contains(id)) {
            currentSelection - id
        } else {
            currentSelection + id
        }
        _state.value = _state.value.copy(selectedChatIds = newSelection)
    }
}
