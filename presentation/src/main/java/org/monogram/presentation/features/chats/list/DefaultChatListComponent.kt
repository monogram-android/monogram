package org.monogram.presentation.features.chats.list

import android.util.Log
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.doOnResume
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.monogram.domain.models.BotMenuButtonModel
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.ChatType
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.UpdateState
import org.monogram.domain.models.stories.ActiveStoryListModel
import org.monogram.domain.models.stories.StoryListType
import org.monogram.domain.repository.AttachMenuBotRepository
import org.monogram.domain.repository.AuthRepository
import org.monogram.domain.repository.AuthStep
import org.monogram.domain.repository.BotRepository
import org.monogram.domain.repository.ChatFolderRepository
import org.monogram.domain.repository.ChatListRepository
import org.monogram.domain.repository.ChatOperationsRepository
import org.monogram.domain.repository.ChatSearchRepository
import org.monogram.domain.repository.ForumTopicsRepository
import org.monogram.domain.repository.ForwardOptions
import org.monogram.domain.repository.ForwardRequest
import org.monogram.domain.repository.ForwardTarget
import org.monogram.domain.repository.MessageRepository
import org.monogram.domain.repository.StoryRepository
import org.monogram.domain.repository.UpdateRepository
import org.monogram.domain.repository.UserProfileEditRepository
import org.monogram.domain.repository.UserRepository
import org.monogram.presentation.BuildConfig
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.core.util.coRunCatching
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.features.chats.common.ChatActionScreenContext
import org.monogram.presentation.features.chats.common.ChatActionState
import org.monogram.presentation.features.chats.common.ChatActionType
import org.monogram.presentation.features.chats.common.ChatExitAction
import org.monogram.presentation.features.chats.common.resolveChatActionPolicy
import org.monogram.presentation.features.chats.common.resolveChatExitAction
import org.monogram.presentation.features.share.ShareTarget
import org.monogram.presentation.root.AppComponentContext

class DefaultChatListComponent(
    context: AppComponentContext,
    private val onSelect: (Long, Long?) -> Unit,
    private val onProfileSelect: (Long) -> Unit,
    private val onSettingsClick: () -> Unit,
    private val onProxySettingsClick: () -> Unit,
    private val onConfirmForward: (ForwardRequest) -> Unit = {},
    private val onShareTargetSelected: (ShareTarget) -> Unit = {},
    private val onShareCancelled: () -> Unit = {},
    private val onShareToStory: (String, String?, String?) -> Unit = { _, _, _ -> },
    private val onStorySelect: (Long, Int?) -> Unit = { _, _ -> },
    private val onAddStory: () -> Unit = {},
    private val forwardingFromChatId: Long? = null,
    private val forwardingMessageIds: List<Long> = emptyList(),
    internal val isForwarding: Boolean = false,
    internal val isShareTargetMode: Boolean = false,
    private val onNewChatClick: () -> Unit = {},
    private val onEditFoldersClick: () -> Unit = {},
    activeChatId: Value<Long>
) : ChatListComponent, AppComponentContext by context {
    private val isTelemtBuild = BuildConfig.ENABLE_TELEMT_DNS

    private val authRepository: AuthRepository = container.repositories.authRepository
    private val chatListRepository: ChatListRepository = container.repositories.chatListRepository
    private val chatFolderRepository: ChatFolderRepository = container.repositories.chatFolderRepository
    private val chatSearchRepository: ChatSearchRepository = container.repositories.chatSearchRepository
    private val chatOperationsRepository: ChatOperationsRepository = container.repositories.chatOperationsRepository
    private val messageRepository: MessageRepository = container.repositories.messageRepository
    private val forumTopicsRepository: ForumTopicsRepository =
        container.repositories.forumTopicsRepository
    private val repositoryUser: UserRepository = container.repositories.userRepository
    private val userProfileEditRepository: UserProfileEditRepository = container.repositories.userProfileEditRepository
    private val botRepository: BotRepository = container.repositories.botRepository
    private val attachMenuBotRepository: AttachMenuBotRepository = container.repositories.attachMenuBotRepository
    private val updateRepository: UpdateRepository = container.repositories.updateRepository
    private val storyRepository: StoryRepository = container.repositories.storyRepository
    override val appPreferences: AppPreferences = container.preferences.appPreferences
    private val messageDisplayer = container.utils.messageDisplayer()

    internal val _state = MutableStateFlow(
        ChatListComponent.State(
            isForwarding = isForwarding,
            isShareTargetMode = isShareTargetMode,
            projectChannelSubscriptionState = appPreferences.projectChannelSubscriptionState(),
            isLoadingByFolder = mapOf(-1 to true)
        )
    )
    private val _uiState = MutableStateFlow(_state.value.toUiState())
    private val _foldersState = MutableStateFlow(_state.value.toFoldersState())
    private val _chatsState = MutableStateFlow(_state.value.toChatsState())
    private val _selectionState = MutableStateFlow(_state.value.toSelectionState())
    private val _searchState = MutableStateFlow(_state.value.toSearchState())
    private val _storiesState = MutableStateFlow(ChatListComponent.StoriesState())

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
    override val storiesState: StateFlow<ChatListComponent.StoriesState> =
        _storiesState.asStateFlow()

    private val scope = componentScope
    private var searchJob: Job? = null
    private var isFetchingMoreMessages = false
    private var nextMessagesOffset = ""
    private var hasRequestedStoryLists = false
    private var hasSeenResume = false
    private val prefetchSemaphore = Semaphore(PREFETCH_CONCURRENCY)
    private val messagePrefetchTimestamps = mutableMapOf<Long, Long>()
    private val storyPrefetchChatIds = mutableSetOf<Long>()
    private val lastPinnedFolderLogByFolder = mutableMapOf<Int, PinnedFolderSnapshot>()

    private fun ChatListComponent.State.toUiState() = ChatListComponent.UiState(
        currentUser = currentUser,
        projectChannelSubscriptionState = projectChannelSubscriptionState,
        isProjectChannelJoinInProgress = isProjectChannelJoinInProgress,
        connectionStatus = connectionStatus,
        isArchivePinned = isArchivePinned,
        isArchiveAlwaysVisible = isArchiveAlwaysVisible,
        isForwarding = isForwarding,
        isShareTargetMode = isShareTargetMode,
        isForwardSubmitInProgress = isForwardSubmitInProgress,
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

    private fun ChatListComponent.State.toSelectionState(): ChatListComponent.SelectionState {
        val selectedChats = resolveSelectedChats(selectedChatIds)
        val selectedForwardChats =
            resolveSelectedChats(selectedForwardTargets.map { it.chatId }.toSet())
        val capabilities = computeSelectionCapabilities(selectedChatIds, selectedChats)
        val allPinned = selectedChats.isNotEmpty() && selectedChats.all { it.isPinned }
        val allMuted = selectedChats.isNotEmpty() && selectedChats.all { it.isMuted }
        val canMarkUnread = selectedChats.isNotEmpty() && selectedChats.none(::hasUnreadState)

        return ChatListComponent.SelectionState(
            selectedChatIds = selectedChatIds,
            selectedForwardTargets = selectedForwardTargets,
            activeChatId = activeChatId,
            selectedChats = selectedChats,
            selectedForwardChats = selectedForwardChats,
            forwardTopicPickerChatId = forwardTopicPickerChatId,
            forwardTopicPickerChatTitle = forwardTopicPickerChatTitle,
            forwardTopics = forwardTopics,
            isLoadingForwardTopics = isLoadingForwardTopics,
            isForwardSubmitInProgress = isForwardSubmitInProgress,
            isShareTargetMode = isShareTargetMode,
            allPinned = allPinned,
            allMuted = allMuted,
            canMarkUnread = canMarkUnread,
            capabilities = capabilities
        )
    }

    private fun ChatListComponent.State.resolveSelectedChats(selectedIds: Set<Long>): List<ChatModel> {
        if (selectedIds.isEmpty()) return emptyList()

        val chatsById = LinkedHashMap<Long, ChatModel>()

        chatsByFolder.values.forEach { chats ->
            chats.forEach { chat ->
                chatsById.putIfAbsent(chat.id, chat)
            }
        }

        searchResults.forEach { chat -> chatsById.putIfAbsent(chat.id, chat) }
        globalSearchResults.forEach { chat -> chatsById.putIfAbsent(chat.id, chat) }
        recentUsers.forEach { chat -> chatsById.putIfAbsent(chat.id, chat) }
        recentOthers.forEach { chat -> chatsById.putIfAbsent(chat.id, chat) }

        return selectedIds.mapNotNull { chatsById[it] }
    }

    private fun ChatListComponent.State.computeSelectionCapabilities(
        selectedIds: Set<Long>,
        selectedChats: List<ChatModel>
    ): ChatListComponent.SelectionCapabilities {
        if (selectedIds.isEmpty()) return ChatListComponent.SelectionCapabilities()

        val hasUnknownSelectedChats = selectedChats.size != selectedIds.size
        if (hasUnknownSelectedChats) {
            return ChatListComponent.SelectionCapabilities()
        }

        val currentFolderChatIds = chatsByFolder[selectedFolderId].orEmpty()
            .asSequence()
            .map { it.id }
            .toSet()

        val canPin = selectedChats.all { currentFolderChatIds.contains(it.id) }
        val singleChat = selectedChats.singleOrNull()
        val singlePolicy = singleChat?.let(::resolveChatListActionPolicy)
        val canDelete = when {
            singlePolicy?.exitAction == ChatExitAction.Delete -> true
            selectedChats.size > 1 -> selectedChats.all { resolveChatListActionPolicy(it).exitAction == ChatExitAction.Delete }
            else -> false
        }
        val canLeave = when {
            singlePolicy?.exitAction == ChatExitAction.Leave -> true
            selectedChats.size > 1 -> selectedChats.all { resolveChatListActionPolicy(it).exitAction == ChatExitAction.Leave }
            else -> false
        }
        val canClearHistory =
            selectedChats.isNotEmpty() && selectedChats.all { resolveChatListActionPolicy(it).canClearHistory }
        val canReport =
            selectedChats.isNotEmpty() && selectedChats.all { resolveChatListActionPolicy(it).canReport }

        return ChatListComponent.SelectionCapabilities(
            canPin = canPin,
            canMute = true,
            canArchive = true,
            canDelete = canDelete,
            canToggleRead = true,
            canLeave = canLeave,
            canClearHistory = canClearHistory,
            canReport = canReport
        )
    }

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
        lifecycle.doOnResume {
            if (!hasSeenResume) {
                hasSeenResume = true
                return@doOnResume
            }
            chatListRepository.refreshOnResume()
        }

        activeChatId.subscribe { id ->
            _state.update { it.copy(activeChatId = id) }
        }

        repositoryUser.currentUserFlow
            .onEach { user ->
                _state.update { it.copy(currentUser = user) }
                if (user != null) {
                    refreshProjectChannelSubscription()
                }
            }
            .launchIn(scope)

        scope.launch {
            repositoryUser.getMe()
        }

        chatFolderRepository.folderChatsFlow
            .onEach { update ->
                val normalizedList = normalizeFolderChats(update.chats)
                val trackedStoryChatIds = storyRepository.activeStories.value.values
                    .asSequence()
                    .flatMap(List<ActiveStoryListModel>::asSequence)
                    .mapTo(mutableSetOf()) { it.chatId }
                val shouldRefreshStories = shouldRefreshStoriesForFolderUpdate(
                    previousChats = _state.value.chatsByFolder[update.folderId],
                    updatedChats = normalizedList,
                    trackedStoryChatIds = trackedStoryChatIds
                )

                val pinnedSnapshot = normalizedList.toPinnedFolderSnapshot()
                if (pinnedSnapshot != null && lastPinnedFolderLogByFolder[update.folderId] != pinnedSnapshot) {
                    lastPinnedFolderLogByFolder[update.folderId] = pinnedSnapshot
                    Log.d(
                        TAG,
                        "folder=${update.folderId} chats=${normalizedList.size} pinned=${pinnedSnapshot.pinnedIds.size} " +
                                "pinnedIds=${pinnedSnapshot.pinnedIds.take(10).joinToString()}"
                    )
                } else if (pinnedSnapshot == null) {
                    lastPinnedFolderLogByFolder.remove(update.folderId)
                }
                _state.update {
                    if (it.chatsByFolder[update.folderId] == normalizedList) {
                        return@update it
                    }
                    val newChatsByFolder = it.chatsByFolder.toMutableMap()
                    newChatsByFolder[update.folderId] = normalizedList
                    it.copy(chatsByFolder = newChatsByFolder)
                }
                syncProjectChannelSubscriptionFromChats(normalizedList)
                prefetchStoryListsForChats(update.folderId, normalizedList)
                if (shouldRefreshStories) {
                    refreshStoriesState("folder_${update.folderId}")
                }
            }
            .launchIn(scope)

        chatFolderRepository.foldersFlow
            .onEach { folders ->
                _state.update { it.copy(folders = folders) }
            }
            .launchIn(scope)

        combine(
            chatFolderRepository.foldersFlow,
            appPreferences.showAllChatsFolder
        ) { folders, showAllChatsFolder ->
            resolvePreferredFolderId(
                folders = folders,
                currentFolderId = _state.value.selectedFolderId,
                showAllChatsFolder = showAllChatsFolder
            )
        }.onEach { targetFolderId ->
            if (targetFolderId != _state.value.selectedFolderId) {
                handleFolderClicked(targetFolderId)
            }
        }.launchIn(scope)

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

        appPreferences.isProjectChannelSubscribed
            .onEach { subscribed ->
                _state.update {
                    it.copy(projectChannelSubscriptionState = subscribed.toProjectChannelSubscriptionState())
                }
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

        storyRepository.activeStories
            .onEach { activeStories ->
                Log.d(
                    STORY_TAG,
                    "story repository update main=${activeStories[StoryListType.MAIN].orEmpty().size} " +
                            "archive=${activeStories[StoryListType.ARCHIVE].orEmpty().size}"
                )
                refreshStoriesState("repository")
            }
            .launchIn(scope)

        storyRepository.storyListChatCounts
            .onEach { counts ->
                Log.d(
                    STORY_TAG,
                    "story list counts update main=${counts[StoryListType.MAIN]} archive=${counts[StoryListType.ARCHIVE]}"
                )
                refreshStoriesState("counts")
            }
            .launchIn(scope)

        authRepository.authState
            .onEach { authState ->
                when (authState) {
                    is AuthStep.Ready -> {
                        if (!hasRequestedStoryLists) {
                            hasRequestedStoryLists = true
                            scope.launch(Dispatchers.IO) {
                                storyRepository.loadActiveStories(StoryListType.MAIN)
                                storyRepository.loadActiveStories(StoryListType.ARCHIVE)
                            }
                        }
                    }

                    else -> {
                        hasRequestedStoryLists = false
                    }
                }
            }
            .launchIn(scope)

        scope.launch {
            if (!isTelemtBuild) {
                updateRepository.checkForUpdates()
            }
            refreshStoriesState("init")
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
                    is ChatListStore.Label.ConfirmForward -> onConfirmForward(label.request)
                    is ChatListStore.Label.ShareTargetSelected -> onShareTargetSelected(label.target)
                    ChatListStore.Label.CancelShareTarget -> onShareCancelled()
                    is ChatListStore.Label.EditFolders -> onEditFoldersClick()
                }
            }
            .launchIn(scope)
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
                lastNonArchiveFolderId = if (id != ARCHIVE_FOLDER_ID) id else it.lastNonArchiveFolderId,
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
            if (folderId != null && folderId != _state.value.selectedFolderId) return@launch
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

    internal fun handleChatClicked(id: Long): ChatListStore.Label? {
        if (_state.value.isShareTargetMode) {
            return resolveShareTarget(id)
        } else if (_state.value.isForwarding) {
            toggleForwardTarget(id)
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

    internal fun handleMessageClicked(chatId: Long, messageId: Long): ChatListStore.Label? {
        if (_state.value.isShareTargetMode) {
            return resolveShareTarget(chatId)
        } else if (_state.value.isForwarding) {
            toggleForwardTarget(chatId)
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
        if (_state.value.isShareTargetMode) return
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
                    statusEmojiPath = statusPath
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
        val selection = _selectionState.value
        if (!selection.capabilities.canMute) return

        val selectedIds = selection.selectedChatIds
        if (selectedIds.isEmpty()) return

        runSelectionAction(ChatActionType.Mute) {
            chatOperationsRepository.toggleMuteChats(selectedIds, mute)
            handleClearSelection()
        }
    }

    override fun onArchiveSelected(archive: Boolean) = store.accept(ChatListStore.Intent.ArchiveSelected(archive))

    internal fun handleArchiveSelected(archive: Boolean) {
        val selection = _selectionState.value
        if (!selection.capabilities.canArchive) return

        val selectedIds = selection.selectedChatIds
        if (selectedIds.isEmpty()) return

        runSelectionAction(ChatActionType.Archive) {
            chatOperationsRepository.toggleArchiveChats(selectedIds, archive)
            handleClearSelection()
        }
    }

    override fun onPinSelected() = store.accept(ChatListStore.Intent.PinSelected)

    internal fun handlePinSelected() {
        val selection = _selectionState.value
        if (!selection.capabilities.canPin) return

        val selectedIds = selection.selectedChatIds
        if (selectedIds.isEmpty()) return

        val shouldPin = selection.selectedChats.any { !it.isPinned }
        val folderId = _state.value.selectedFolderId

        runSelectionAction(ChatActionType.Pin) {
            chatOperationsRepository.togglePinChats(selectedIds, shouldPin, folderId)
            handleClearSelection()
        }
    }

    override fun onToggleReadSelected() = store.accept(ChatListStore.Intent.ToggleReadSelected)

    internal fun handleToggleReadSelected() {
        val selection = _selectionState.value
        if (!selection.capabilities.canToggleRead) return

        val selectedIds = selection.selectedChatIds
        if (selectedIds.isEmpty()) return

        val shouldMarkUnread = selection.canMarkUnread

        runSelectionAction(ChatActionType.ToggleRead) {
            chatOperationsRepository.toggleReadChats(selectedIds, shouldMarkUnread)
            handleClearSelection()
        }
    }

    override fun onDeleteSelected() = store.accept(ChatListStore.Intent.DeleteSelected)

    internal fun handleDeleteSelected() {
        val selection = _selectionState.value
        if (!selection.capabilities.canDelete) return

        val selectedIds = selection.selectedChatIds
        if (selectedIds.isEmpty()) return

        runSelectionAction(ChatActionType.Delete) {
            chatOperationsRepository.deleteChats(selectedIds)
            handleClearSelection()
        }
    }

    override fun onMarkCurrentFolderRead() =
        store.accept(ChatListStore.Intent.MarkCurrentFolderRead)

    internal fun handleMarkCurrentFolderRead() {
        val state = _state.value
        val unreadChatIds = state.chats
            .asSequence()
            .filter(::hasUnreadState)
            .map { it.id }
            .toSet()
        if (unreadChatIds.isEmpty()) return

        scope.launch(Dispatchers.IO) {
            chatOperationsRepository.markFolderAsRead(state.selectedFolderId, unreadChatIds)
        }
    }

    override fun onLeaveSelected() = store.accept(ChatListStore.Intent.LeaveSelected)

    internal fun handleLeaveSelected() {
        val selection = _selectionState.value
        if (!selection.capabilities.canLeave) return
        val selectedIds = selection.selectedChatIds
        if (selectedIds.isEmpty()) return

        runSelectionAction(ChatActionType.Leave) {
            chatOperationsRepository.leaveChats(selectedIds)
            handleClearSelection()
        }
    }

    override fun onClearHistorySelected(revoke: Boolean) =
        store.accept(ChatListStore.Intent.ClearHistorySelected(revoke))

    internal fun handleClearHistorySelected(revoke: Boolean) {
        val selection = _selectionState.value
        if (!selection.capabilities.canClearHistory) return
        val selectedIds = selection.selectedChatIds
        if (selectedIds.isEmpty()) return

        runSelectionAction(ChatActionType.ClearHistory) {
            chatOperationsRepository.clearChatHistories(selectedIds, revoke)
            handleClearSelection()
        }
    }

    override fun onReportSelected(reason: String) =
        store.accept(ChatListStore.Intent.ReportSelected(reason))

    internal fun handleReportSelected(reason: String) {
        val selection = _selectionState.value
        if (!selection.capabilities.canReport) return
        val selectedIds = selection.selectedChatIds
        if (selectedIds.isEmpty()) return

        runSelectionAction(ChatActionType.Report) {
            chatOperationsRepository.reportChats(selectedIds, reason)
            handleClearSelection()
        }
    }

    override fun onArchivePinToggle() = store.accept(ChatListStore.Intent.ArchivePinToggle)

    internal fun handleArchivePinToggle() {
        chatOperationsRepository.setArchivePinned(!_state.value.isArchivePinned)
    }

    override fun onConfirmForwarding(
        sendCopy: Boolean,
        removeCaption: Boolean,
        commentText: String,
        commentEntities: List<MessageEntity>
    ) = store.accept(
        ChatListStore.Intent.ConfirmForwarding(
            sendCopy = sendCopy,
            removeCaption = removeCaption,
            commentText = commentText,
            commentEntities = commentEntities
        )
    )

    internal fun handleConfirmForwarding(
        sendCopy: Boolean,
        removeCaption: Boolean,
        commentText: String,
        commentEntities: List<MessageEntity>
    ): ChatListStore.Label.ConfirmForward? {
        val fromChatId = forwardingFromChatId ?: return null
        val messageIds = forwardingMessageIds.takeIf { it.isNotEmpty() } ?: return null
        val state = _state.value
        if (state.isForwardSubmitInProgress) return null
        val targets = state.selectedForwardTargets.takeIf { it.isNotEmpty() } ?: return null
        val request = ForwardRequest(
            fromChatId = fromChatId,
            messageIds = messageIds,
            targets = targets,
            options = ForwardOptions(
                sendCopy = sendCopy,
                removeCaption = removeCaption && sendCopy,
                commentText = commentText,
                commentEntities = commentEntities
            )
        )
        _state.update {
            it.copy(
                isForwardSubmitInProgress = true,
                selectedForwardTargets = emptyList(),
                forwardTopicPickerChatId = null,
                forwardTopicPickerChatTitle = "",
                forwardTopics = emptyList(),
                isLoadingForwardTopics = false
            )
        }
        return ChatListStore.Label.ConfirmForward(request)
    }

    override fun onForwardTopicSelected(chatId: Long, topicId: Int?) =
        store.accept(ChatListStore.Intent.ForwardTopicSelected(chatId, topicId))

    internal fun handleForwardTopicSelected(chatId: Long, topicId: Int?) {
        val target = ForwardTarget(chatId = chatId, forumTopicId = topicId)
        _state.update { state ->
            val withoutSameTarget = state.selectedForwardTargets.filterNot {
                it.chatId == target.chatId && it.forumTopicId == target.forumTopicId
            }
            state.copy(
                selectedForwardTargets = withoutSameTarget + target,
                forwardTopicPickerChatId = null,
                forwardTopicPickerChatTitle = "",
                forwardTopics = emptyList(),
                isLoadingForwardTopics = false
            )
        }
    }

    override fun onDismissForwardTopicPicker() =
        store.accept(ChatListStore.Intent.DismissForwardTopicPicker)

    internal fun handleDismissForwardTopicPicker() {
        _state.update {
            it.copy(
                forwardTopicPickerChatId = null,
                forwardTopicPickerChatTitle = "",
                forwardTopics = emptyList(),
                isLoadingForwardTopics = false
            )
        }
    }

    override fun onShareTopicSelected(chatId: Long, topicId: Int?) =
        store.accept(ChatListStore.Intent.ShareTopicSelected(chatId, topicId))

    internal fun handleShareTopicSelected(
        chatId: Long,
        topicId: Int?
    ): ChatListStore.Label.ShareTargetSelected {
        handleDismissForwardTopicPicker()
        return ChatListStore.Label.ShareTargetSelected(
            ShareTarget(chatId = chatId, topicId = topicId?.toLong())
        )
    }

    override fun onDismissShareTopicPicker() =
        store.accept(ChatListStore.Intent.DismissShareTopicPicker)

    internal fun handleDismissShareTopicPicker(): ChatListStore.Label.CancelShareTarget? {
        if (!_state.value.isShareTargetMode) {
            handleDismissForwardTopicPicker()
            return null
        }
        handleDismissForwardTopicPicker()
        return ChatListStore.Label.CancelShareTarget
    }

    override fun onRemoveForwardTarget(target: ForwardTarget) =
        store.accept(ChatListStore.Intent.RemoveForwardTarget(target))

    internal fun handleRemoveForwardTarget(target: ForwardTarget) {
        _state.update {
            it.copy(selectedForwardTargets = it.selectedForwardTargets.filterNot { selected -> selected == target })
        }
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
                val nextFolderId = resolvePreferredFolderId(
                    folders = _state.value.folders.filterNot { it.id == folderId },
                    currentFolderId = null,
                    showAllChatsFolder = appPreferences.showAllChatsFolder.value
                )
                handleFolderClicked(nextFolderId)
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

    override fun onShareToStory(mediaUrl: String, text: String?, widgetLink: String?) {
        onShareToStory.invoke(mediaUrl, text, widgetLink)
    }

    override fun onStoryClicked(chatId: Long, storyId: Int?) {
        onStorySelect(chatId, storyId)
    }

    override fun onAddStoryClicked() {
        onAddStory()
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

    override fun onProjectChannelSubscribe() =
        store.accept(ChatListStore.Intent.ProjectChannelSubscribe)

    internal fun handleProjectChannelSubscribe() {
        if (_state.value.isProjectChannelJoinInProgress) return
        if (_state.value.projectChannelSubscriptionState == ChatListComponent.ProjectChannelSubscriptionState.SUBSCRIBED) {
            return
        }

        _state.update { it.copy(isProjectChannelJoinInProgress = true) }
        scope.launch(Dispatchers.IO) {
            runCatching {
                messageRepository.joinChat(PROJECT_CHANNEL_CHAT_ID)
                appPreferences.setProjectChannelPromoDismissed(false)
            }.onSuccess {
                val chat = runCatching {
                    chatListRepository.getChatById(PROJECT_CHANNEL_CHAT_ID)
                }.getOrNull()

                if (chat != null) {
                    syncProjectChannelSubscriptionFromChat(chat, clearJoinInProgress = true)
                } else {
                    _state.update { it.copy(isProjectChannelJoinInProgress = false) }
                    refreshProjectChannelSubscription()
                }
            }.onFailure { error ->
                _state.update { it.copy(isProjectChannelJoinInProgress = false) }
                messageDisplayer.show(error.message ?: "Unable to subscribe right now")
                refreshProjectChannelSubscription()
            }
        }
    }

    override fun onProjectChannelLater() =
        store.accept(ChatListStore.Intent.ProjectChannelLater)

    internal fun handleProjectChannelLater() {
        appPreferences.setProjectChannelPromoDismissed(true)
    }

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

            state.value.forwardTopicPickerChatId != null -> {
                if (state.value.isShareTargetMode) {
                    handleDismissShareTopicPicker()
                } else {
                    handleDismissForwardTopicPicker()
                }
                true
            }

            state.value.isShareTargetMode -> {
                onShareCancelled()
                true
            }
            state.value.selectedForwardTargets.isNotEmpty() -> {
                _state.update { it.copy(selectedForwardTargets = emptyList()) }
                true
            }
            state.value.selectedChatIds.isNotEmpty() -> {
                handleClearSelection()
                true
            }
            state.value.selectedFolderId == ARCHIVE_FOLDER_ID -> {
                handleFolderClicked(
                    resolveArchiveReturnFolderId(
                        folders = _state.value.folders,
                        showAllChatsFolder = appPreferences.showAllChatsFolder.value,
                        lastNonArchiveFolderId = _state.value.lastNonArchiveFolderId,
                    )
                )
                true
            }

            state.value.isForwarding -> {
                onSelect(0L, null)
                true
            }
            else -> false
        }
    }

    override fun onVisibleChatIdsChanged(ids: List<Long>) =
        store.accept(ChatListStore.Intent.VisibleChatIdsChanged(ids))

    internal fun handleVisibleChatIdsChanged(ids: List<Long>) {
        val state = _state.value
        if (state.isSearchActive || state.selectedFolderId == -2 || ids.isEmpty()) return

        val chatsById = state.chats.associateBy { it.id }
        val now = System.currentTimeMillis()
        ids.asSequence()
            .mapNotNull { chatsById[it] }
            .distinctBy { it.id }
            .take(PREFETCH_MAX_CANDIDATES)
            .filter { chat ->
                val lastPrefetchAt = messagePrefetchTimestamps[chat.id] ?: 0L
                now - lastPrefetchAt >= PREFETCH_TTL_MS
            }
            .forEach { chat ->
                messagePrefetchTimestamps[chat.id] = now
                scope.launch(Dispatchers.IO) {
                    prefetchSemaphore.withPermit {
                        prefetchMessagesForChat(chat)
                    }
                }
            }
    }

    private suspend fun prefetchMessagesForChat(chat: ChatModel) {
        coRunCatching {
            messageRepository.getMessagesOlder(
                chatId = chat.id,
                fromMessageId = 0L,
                limit = PREFETCH_PAGE_SIZE
            )
            if (chat.unreadCount > 0 && chat.lastReadInboxMessageId > 0L) {
                messageRepository.getMessagesNewer(
                    chatId = chat.id,
                    fromMessageId = chat.lastReadInboxMessageId,
                    limit = PREFETCH_PAGE_SIZE
                )
            }
        }.onFailure { error ->
            Log.d(TAG, "message prefetch failed chatId=${chat.id}: ${error.message}")
        }
    }

    private fun refreshProjectChannelSubscription() {
        scope.launch(Dispatchers.IO) {
            val chat = runCatching {
                chatListRepository.getChatById(PROJECT_CHANNEL_CHAT_ID)
            }.getOrNull()

            if (chat != null) {
                syncProjectChannelSubscriptionFromChat(chat)
                return@launch
            }

            val currentState = _state.value.projectChannelSubscriptionState
            if (currentState == ChatListComponent.ProjectChannelSubscriptionState.SUBSCRIBED) {
                appPreferences.setProjectChannelSubscribed(true)
            }
        }
    }

    private fun prefetchStoryListsForChats(folderId: Int, chats: List<ChatModel>) {
        val hintedChats = chats.asSequence()
            .filter { it.activeStoryId != 0 || !it.activeStoryStateType.isNullOrBlank() }
            .filter { chat ->
                storyRepository.activeStories.value.values
                    .asSequence()
                    .flatMap(List<ActiveStoryListModel>::asSequence)
                    .none { active -> active.chatId == chat.id }
            }
            .filter { chat -> storyPrefetchChatIds.add(chat.id) }
            .take(STORY_PREFETCH_MAX)
            .toList()

        if (hintedChats.isEmpty()) return

        scope.launch(Dispatchers.IO) {
            hintedChats.forEach { chat ->
                Log.d(
                    STORY_TAG,
                    "prefetch story list folder=$folderId chatId=${chat.id} hintType=${chat.activeStoryStateType} hintId=${chat.activeStoryId}"
                )
                coRunCatching {
                    storyRepository.getChatActiveStories(chat.id)
                }.onFailure { error ->
                    Log.d(
                        STORY_TAG,
                        "prefetch story list failed chatId=${chat.id}: ${error.message}"
                    )
                }
            }
            refreshStoriesState("prefetch_$folderId")
        }
    }

    private fun refreshStoriesState(reason: String) {
        scope.launch(Dispatchers.IO) {
            val repositoryStories = storyRepository.activeStories.value
            val storyListChatCounts = storyRepository.storyListChatCounts.value
            val combinedStories = repositoryStories[StoryListType.MAIN].orEmpty() +
                    repositoryStories[StoryListType.ARCHIVE].orEmpty()
            val archiveFolderChatIds = _state.value.chatsByFolder[ARCHIVE_FOLDER_ID]
                .orEmpty()
                .mapTo(mutableSetOf()) { it.id }
            val localChatIndex = LinkedHashMap<Long, ChatModel>()
            _state.value.chatsByFolder.values.forEach { chats ->
                chats.forEach { chat -> localChatIndex.putIfAbsent(chat.id, chat) }
            }

            val resolvedStories = combinedStories.mapNotNull { storyList ->
                val chat = localChatIndex[storyList.chatId] ?: chatListRepository.getChatById(
                    storyList.chatId
                )
                if (chat == null) {
                    Log.d(
                        STORY_TAG,
                        "story chat metadata missing chatId=${storyList.chatId} reason=$reason"
                    )
                    null
                } else {
                    val isArchived = when {
                        storyList.chatId in archiveFolderChatIds -> true
                        else -> chatListRepository.isChatArchived(storyList.chatId)
                            ?: chat.isArchived
                    }
                    Triple(chat, storyList, isArchived)
                }
            }

            val visibleStories = resolvedStories.groupBy(
                keySelector = { (_, stories, _) -> stories.listType },
                valueTransform = { (_, stories, _) -> stories }
            )
            val archivedChatIds = resolvedStories
                .asSequence()
                .filter { (_, _, isArchived) -> isArchived }
                .mapTo(mutableSetOf()) { (_, stories, _) -> stories.chatId }
            val (mainStories, archiveStories) = resolveDisplayedStoryLists(
                repositoryStories = visibleStories,
                archivedChatIds = archivedChatIds
            )
            val isMainStoriesLoaded = storyListChatCounts.containsKey(StoryListType.MAIN) ||
                    repositoryStories.containsKey(StoryListType.MAIN)
            val isArchiveStoriesLoaded = storyListChatCounts.containsKey(StoryListType.ARCHIVE) ||
                    repositoryStories.containsKey(StoryListType.ARCHIVE)

            val newStoriesState = ChatListComponent.StoriesState(
                mainActiveStories = mainStories,
                archiveActiveStories = archiveStories,
                isMainStoriesLoaded = isMainStoriesLoaded,
                isArchiveStoriesLoaded = isArchiveStoriesLoaded
            )

            if (newStoriesState == _storiesState.value) {
                return@launch
            }

            Log.d(
                STORY_TAG,
                "refreshStoriesState reason=$reason total=${combinedStories.size} main=${mainStories.size} archived=${archiveStories.size} archiveFolderIds=${archiveFolderChatIds.size} mainLoaded=$isMainStoriesLoaded archiveLoaded=$isArchiveStoriesLoaded"
            )

            _storiesState.value = newStoriesState
        }
    }

    private fun syncProjectChannelSubscriptionFromChats(chats: List<ChatModel>) {
        chats.firstOrNull { it.id == PROJECT_CHANNEL_CHAT_ID }
            ?.let(::syncProjectChannelSubscriptionFromChat)
    }

    private fun syncProjectChannelSubscriptionFromChat(
        chat: ChatModel,
        clearJoinInProgress: Boolean = false
    ) {
        val subscribed = chat.isMember
        appPreferences.setProjectChannelSubscribed(subscribed)
        _state.update {
            it.copy(
                projectChannelSubscriptionState = subscribed.toProjectChannelSubscriptionState(),
                isProjectChannelJoinInProgress = if (subscribed || clearJoinInProgress) {
                    false
                } else {
                    it.isProjectChannelJoinInProgress
                }
            )
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
        private const val STORY_TAG = "StoriesDiag"
        private const val ARCHIVE_FOLDER_ID = -2
        private const val PROJECT_CHANNEL_CHAT_ID = -1003768707135L
        private const val PREFETCH_MAX_CANDIDATES = 6
        private const val PREFETCH_CONCURRENCY = 2
        private const val PREFETCH_PAGE_SIZE = 30
        private const val PREFETCH_TTL_MS = 60_000L
        private const val STORY_PREFETCH_MAX = 12
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

    private fun runSelectionAction(
        action: ChatActionType,
        block: suspend () -> Unit
    ) {
        if (_state.value.actionState is ChatActionState.Pending) return
        scope.launch(Dispatchers.IO) {
            _state.update { it.copy(actionState = ChatActionState.Pending(action)) }
            runCatching { block() }
                .onSuccess {
                    _state.update { it.copy(actionState = ChatActionState.Success(action)) }
                }
                .onFailure { error ->
                    val message = error.message ?: "Action failed"
                    _state.update {
                        it.copy(
                            actionState = ChatActionState.Failure(
                                action,
                                message
                            )
                        )
                    }
                    messageDisplayer.show(message)
                }
            if (_state.value.actionState !is ChatActionState.Pending) {
                _state.update { it.copy(actionState = ChatActionState.Idle) }
            }
        }
    }

    private fun toggleForwardTarget(id: Long) {
        val chat = _state.value.resolveSelectedChats(setOf(id)).firstOrNull()
        if (chat?.viewAsTopics == true) {
            openForwardTopicPicker(chat)
            return
        }

        val target = ForwardTarget(chatId = id)
        val currentTargets = _state.value.selectedForwardTargets
        val newTargets = if (currentTargets.contains(target)) {
            currentTargets - target
        } else {
            currentTargets + target
        }
        _state.update { it.copy(selectedForwardTargets = newTargets) }
    }

    private fun resolveShareTarget(id: Long): ChatListStore.Label? {
        val chat = _state.value.resolveSelectedChats(setOf(id)).firstOrNull() ?: return null
        if (chat.viewAsTopics) {
            openForwardTopicPicker(chat)
            return null
        }
        return ChatListStore.Label.ShareTargetSelected(ShareTarget(chatId = id))
    }

    private fun openForwardTopicPicker(chat: ChatModel) {
        _state.update {
            it.copy(
                forwardTopicPickerChatId = chat.id,
                forwardTopicPickerChatTitle = chat.title,
                forwardTopics = emptyList(),
                isLoadingForwardTopics = true
            )
        }
        scope.launch(Dispatchers.IO) {
            val topics = coRunCatching {
                forumTopicsRepository.getForumTopics(chat.id, limit = 100)
            }.getOrElse { emptyList() }
            _state.update { state ->
                if (state.forwardTopicPickerChatId != chat.id) {
                    state
                } else {
                    state.copy(
                        forwardTopics = topics,
                        isLoadingForwardTopics = false
                    )
                }
            }
        }
    }

    private fun resolvePreferredFolderId(
        folders: List<org.monogram.domain.models.FolderModel>,
        currentFolderId: Int?,
        showAllChatsFolder: Boolean
    ): Int {
        val userFolders = folders.filter { it.id >= 0 }
        val allChatsFolder = folders.firstOrNull { it.id == -1 }
        val visibleFolderIds = if (showAllChatsFolder || userFolders.isEmpty()) {
            folders.map { it.id }
        } else {
            folders.filterNot { it.id == -1 }.map { it.id }
        }

        return when {
            currentFolderId == -2 -> -2
            currentFolderId != null && currentFolderId in visibleFolderIds -> currentFolderId
            userFolders.isNotEmpty() -> userFolders.first().id
            allChatsFolder != null -> allChatsFolder.id
            else -> -1
        }
    }
}

internal fun resolveDisplayedStoryLists(
    repositoryStories: Map<StoryListType, List<ActiveStoryListModel>>,
    archivedChatIds: Set<Long>
): Pair<List<ActiveStoryListModel>, List<ActiveStoryListModel>> {
    val explicitArchiveStories = repositoryStories[StoryListType.ARCHIVE].orEmpty()
    val explicitArchiveIds = explicitArchiveStories.mapTo(mutableSetOf()) { it.chatId }
    val mainStories = repositoryStories[StoryListType.MAIN].orEmpty()
        .filterNot { it.chatId in archivedChatIds || it.chatId in explicitArchiveIds }
    val archiveFallbackStories = repositoryStories[StoryListType.MAIN].orEmpty()
        .filter { it.chatId in archivedChatIds && it.chatId !in explicitArchiveIds }
    return mainStories to (explicitArchiveStories + archiveFallbackStories)
}

private fun hasUnreadState(chat: ChatModel): Boolean {
    return chat.unreadCount > 0 ||
            chat.isMarkedAsUnread ||
            chat.unreadMentionCount > 0 ||
            chat.unreadReactionCount > 0
}

internal fun resolveChatListExitAction(chat: ChatModel): ChatExitAction {
    return resolveChatExitAction(
        isMainChat = true,
        isGroup = chat.isGroup,
        isChannel = chat.isChannel,
        isMember = chat.isMember,
        canDeleteChat = chat.canBeDeletedOnlyForSelf || chat.canBeDeletedForAllUsers
    )
}

internal fun resolveChatListActionPolicy(chat: ChatModel) =
    resolveChatActionPolicy(
        isMainChat = true,
        isGroup = chat.isGroup,
        isChannel = chat.isChannel,
        isMember = chat.isMember,
        canDeleteChat = chat.canBeDeletedOnlyForSelf || chat.canBeDeletedForAllUsers,
        canReport = chat.canBeReported,
        canJoin = (chat.isGroup || chat.isChannel) && !chat.isMember,
        canBlockOrUnblock = !chat.isGroup && !chat.isChannel,
        canPin = true,
        context = ChatActionScreenContext.ListSelection
    )

internal fun normalizeFolderChats(chats: List<ChatModel>): List<ChatModel> {
    if (chats.size < 2) return chats

    val seen = HashSet<Long>(chats.size)
    val normalized = ArrayList<ChatModel>(chats.size)
    chats.forEach { chat ->
        if (seen.add(chat.id)) {
            normalized.add(chat)
        }
    }
    return if (normalized.size == chats.size) chats else normalized
}

internal fun shouldRefreshStoriesForFolderUpdate(
    previousChats: List<ChatModel>?,
    updatedChats: List<ChatModel>,
    trackedStoryChatIds: Set<Long>
): Boolean {
    return previousChats.toStoryRefreshSnapshot(trackedStoryChatIds) !=
            updatedChats.toStoryRefreshSnapshot(trackedStoryChatIds)
}

private fun List<ChatModel>?.toStoryRefreshSnapshot(
    trackedStoryChatIds: Set<Long>
): Map<Long, StoryRefreshSnapshot> {
    return this.orEmpty()
        .asSequence()
        .filter { chat ->
            chat.id in trackedStoryChatIds ||
                    chat.isArchived ||
                    chat.activeStoryId != 0 ||
                    !chat.activeStoryStateType.isNullOrBlank()
        }
        .associate { chat ->
            chat.id to StoryRefreshSnapshot(
                id = chat.id,
                isArchived = chat.isArchived,
                activeStoryStateType = chat.activeStoryStateType,
                activeStoryId = chat.activeStoryId
            )
        }
}

private data class StoryRefreshSnapshot(
    val id: Long,
    val isArchived: Boolean,
    val activeStoryStateType: String?,
    val activeStoryId: Int
)

private data class PinnedFolderSnapshot(
    val pinnedIds: List<Long>
)

private fun List<ChatModel>.toPinnedFolderSnapshot(): PinnedFolderSnapshot? {
    val pinnedIds = asSequence()
        .filter { it.isPinned }
        .map { it.id }
        .toList()
    return pinnedIds.takeIf(List<Long>::isNotEmpty)?.let(::PinnedFolderSnapshot)
}

private fun AppPreferences.projectChannelSubscriptionState(): ChatListComponent.ProjectChannelSubscriptionState {
    return isProjectChannelSubscribed.value.toProjectChannelSubscriptionState()
}

private fun Boolean.toProjectChannelSubscriptionState(): ChatListComponent.ProjectChannelSubscriptionState {
    return if (this) {
        ChatListComponent.ProjectChannelSubscriptionState.SUBSCRIBED
    } else {
        ChatListComponent.ProjectChannelSubscriptionState.NOT_SUBSCRIBED
    }
}
