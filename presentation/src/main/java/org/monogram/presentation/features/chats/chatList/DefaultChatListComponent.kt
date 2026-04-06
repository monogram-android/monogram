package org.monogram.presentation.features.chats.chatList

import android.util.Log
import com.arkivanov.decompose.value.Value
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.monogram.domain.models.BotMenuButtonModel
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

    private val _state = MutableStateFlow(
        ChatListComponent.State(
            isForwarding = isForwarding,
            isLoadingByFolder = mapOf(-1 to true)
        )
    )

    private val store = instanceKeeper.getStore {
        ChatListStoreFactory(
            storeFactory = DefaultStoreFactory(),
            component = this
        ).create()
    }

    override val state: StateFlow<ChatListComponent.State> = store.stateFlow

    private val scope = componentScope
    private var searchJob: Job? = null
    private var isFetchingMoreMessages = false
    private var nextMessagesOffset = ""

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
                _state.update { it.copy(searchHistory = history) }
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
            store.accept(ChatListStore.Intent.UpdateState(it))
        }.launchIn(scope)

        scope.launch(Dispatchers.IO) {
            chatListRepository.selectFolder(_state.value.selectedFolderId)
        }
    }

    override fun retryConnection() {
        chatListRepository.retryConnection()
    }

    override fun onFolderClicked(id: Int) {
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

    override fun loadMore(folderId: Int?) {
        val targetFolderId = folderId ?: _state.value.selectedFolderId
        if (_state.value.isLoadingByFolder[targetFolderId] == true) return

        scope.launch(Dispatchers.IO) {
            if (folderId != null && folderId != _state.value.selectedFolderId) {
                return@launch
            }
            chatListRepository.loadNextChunk(20)
        }
    }

    override fun loadMoreMessages() {
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

    override fun onChatClicked(id: Long) {
        if (_state.value.isForwarding) {
            toggleSelection(id)
        } else if (_state.value.selectedChatIds.isNotEmpty()) {
            toggleSelection(id)
        } else {
            if (_state.value.isSearchActive) {
                chatSearchRepository.addSearchChatId(id)
            }
            onSelect(id, null)
        }
    }

    override fun onProfileClicked(id: Long) {
        onProfileSelect(id)
    }

    override fun onMessageClicked(chatId: Long, messageId: Long) {
        if (_state.value.isForwarding) {
            toggleSelection(chatId)
        } else if (_state.value.selectedChatIds.isNotEmpty()) {
            toggleSelection(chatId)
        } else {
            if (_state.value.isSearchActive) {
                chatSearchRepository.addSearchChatId(chatId)
            }
            onSelect(chatId, messageId)
        }
    }

    override fun onChatLongClicked(id: Long) {
        toggleSelection(id)
    }

    override fun clearSelection() {
        _state.update { it.copy(selectedChatIds = emptySet()) }
    }

    override fun onSettingsClicked() {
        onSettingsClick()
    }

    override fun onSearchToggle() {
        _state.update {
            it.copy(
                isSearchActive = !it.isSearchActive,
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

    override fun onClearSearchHistory() {
        chatSearchRepository.clearSearchHistory()
    }

    override fun onRemoveSearchHistoryItem(chatId: Long) {
        chatSearchRepository.removeSearchChatId(chatId)
    }

    override fun onMuteSelected(mute: Boolean) {
        val selectedIds = _state.value.selectedChatIds
        val selectedChats = _state.value.chats.filter { selectedIds.contains(it.id) }
        val shouldMute = selectedChats.any { !it.isMuted }

        scope.launch(Dispatchers.IO) {
            chatOperationsRepository.toggleMuteChats(selectedIds, shouldMute)
            clearSelection()
        }
    }

    override fun onArchiveSelected(archive: Boolean) {
        val selectedIds = _state.value.selectedChatIds
        scope.launch(Dispatchers.IO) {
            chatOperationsRepository.toggleArchiveChats(selectedIds, archive)
            clearSelection()
        }
    }

    override fun onPinSelected() {
        val selectedIds = _state.value.selectedChatIds
        val selectedChats = _state.value.chats.filter { selectedIds.contains(it.id) }
        val shouldPin = selectedChats.any { !it.isPinned }
        val folderId = _state.value.selectedFolderId

        scope.launch(Dispatchers.IO) {
            chatOperationsRepository.togglePinChats(selectedIds, shouldPin, folderId)
            clearSelection()
        }
    }

    override fun onToggleReadSelected() {
        val selectedIds = _state.value.selectedChatIds
        val selectedChats = _state.value.chats.filter { selectedIds.contains(it.id) }
        val shouldMarkUnread = selectedChats.any { !it.isMarkedAsUnread }

        scope.launch(Dispatchers.IO) {
            chatOperationsRepository.toggleReadChats(selectedIds, shouldMarkUnread)
            clearSelection()
        }
    }

    override fun onDeleteSelected() {
        val selectedIds = _state.value.selectedChatIds
        scope.launch(Dispatchers.IO) {
            chatOperationsRepository.deleteChats(selectedIds)
            clearSelection()
        }
    }

    override fun onArchivePinToggle() {
        chatOperationsRepository.setArchivePinned(!_state.value.isArchivePinned)
    }

    override fun onConfirmForwarding() {
        if (_state.value.selectedChatIds.isNotEmpty()) {
            onConfirmForward(_state.value.selectedChatIds)
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
            if (_state.value.selectedFolderId == folderId) {
                onFolderClicked(-1)
            }
        }
    }

    override fun onEditFolder(folderId: Int) {
        if (folderId <= 0) return
        onEditFoldersClick()
    }

    override fun onOpenInstantView(url: String) {
        _state.update { it.copy(instantViewUrl = url) }
    }

    override fun onDismissInstantView() {
        _state.update { it.copy(instantViewUrl = null) }
    }

    override fun onOpenWebApp(url: String, botUserId: Long, botName: String) {
        _state.update {
            it.copy(
                webAppUrl = url,
                webAppBotId = botUserId,
                webAppBotName = botName
            )
        }
    }

    override fun onDismissWebApp() {
        _state.update {
            it.copy(
                webAppUrl = null,
                webAppBotId = null,
                webAppBotName = null
            )
        }
    }

    override fun onOpenWebView(url: String) {
        _state.update { it.copy(webViewUrl = url) }
    }

    override fun onDismissWebView() {
        _state.update { it.copy(webViewUrl = null) }
    }

    override fun onUpdateClicked() {
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
                onSettingsClick()
            }
        }
    }

    override fun handleBack(): Boolean {
        return when {
            state.value.webViewUrl != null -> {
                onDismissWebView()
                true
            }
            state.value.webAppUrl != null -> {
                onDismissWebApp()
                true
            }
            state.value.instantViewUrl != null -> {
                onDismissInstantView()
                true
            }
            state.value.isSearchActive -> {
                onSearchToggle()
                true
            }
            state.value.selectedChatIds.isNotEmpty() -> {
                clearSelection()
                true
            }
            state.value.selectedFolderId == -2 -> {
                onFolderClicked(-1)
                true
            }

            state.value.isForwarding -> {
                onSelect(0L, null)
                true
            }
            else -> false
        }
    }

    override fun updateScrollPosition(folderId: Int, index: Int, offset: Int) {
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
        _state.update { state ->
            val newSelection = if (state.selectedChatIds.contains(id)) {
                state.selectedChatIds - id
            } else {
                state.selectedChatIds + id
            }
            state.copy(selectedChatIds = newSelection)
        }
    }
}
