package org.monogram.presentation.features.profile

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.monogram.domain.models.BotMenuButtonModel
import org.monogram.domain.models.ChatInteractionType
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.ChatPermissionsModel
import org.monogram.domain.models.ChatRevenueStatisticsModel
import org.monogram.domain.models.ChatStatisticsModel
import org.monogram.domain.models.FileDownloadEvent
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.ProfilePhotoMedia
import org.monogram.domain.models.StatisticsGraphModel
import org.monogram.domain.models.UserModel
import org.monogram.domain.models.UserProfileSnapshotModel
import org.monogram.domain.models.UserTypeEnum
import org.monogram.domain.repository.BotPreferencesProvider
import org.monogram.domain.repository.BotRepository
import org.monogram.domain.repository.ChatInfoRepository
import org.monogram.domain.repository.ChatListRepository
import org.monogram.domain.repository.ChatMemberStatus
import org.monogram.domain.repository.ChatMembersFilter
import org.monogram.domain.repository.ChatOperationsRepository
import org.monogram.domain.repository.ChatSettingsRepository
import org.monogram.domain.repository.ChatStatisticsRepository
import org.monogram.domain.repository.ConversationKey
import org.monogram.domain.repository.ConversationScope
import org.monogram.domain.repository.GifRepository
import org.monogram.domain.repository.HistoryAnchor
import org.monogram.domain.repository.HistoryDirection
import org.monogram.domain.repository.HistoryRequest
import org.monogram.domain.repository.HistorySource
import org.monogram.domain.repository.LocationRepository
import org.monogram.domain.repository.MessageRepository
import org.monogram.domain.repository.PrivacyRepository
import org.monogram.domain.repository.ProfilePhotoRepository
import org.monogram.domain.repository.StoryRepository
import org.monogram.domain.repository.TelegramBackendMode
import org.monogram.domain.repository.TelegramLinkRepository
import org.monogram.domain.repository.UserProfileSnapshotRepository
import org.monogram.domain.repository.UserRepository
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.core.util.coRunCatching
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.features.chats.common.ChatActionState
import org.monogram.presentation.features.chats.common.ChatActionType
import org.monogram.presentation.root.AppComponentContext

private const val DEFAULT_ACCOUNT_ID = "default"

class DefaultProfileComponent(
    context: AppComponentContext,
    private val chatId: Long,
    private val onBackClicked: () -> Unit,
    private val onMessageClicked: (MessageModel) -> Unit = {},
    private val onMessageLongClicked: (MessageModel) -> Unit = {},
    private val onAvatarClicked: (String) -> Unit = {},
    private val onEditClicked: () -> Unit = {},
    private val onSendMessageClicked: (Long) -> Unit = {},
    private val onShowLogsClicked: (Long) -> Unit = {},
    private val onEditContactClicked: (Long) -> Unit = {},
    private val onMemberClicked: (Long) -> Unit = {},
    private val onMemberLongClicked: (Long, Long) -> Unit = { _, _ -> },
    private val onOpenStoriesClicked: (Long, Int?) -> Unit = { _, _ -> },
    private val onOpenPostedStoriesClicked: (Long, Int?) -> Unit = { _, _ -> },
    private val onOpenStoryArchiveClicked: (Long) -> Unit = {},
    private val onCreateStoryClicked: (Long) -> Unit = {},
    private val onShareToStoryRequested: (String, String?, String?) -> Unit = { _, _, _ -> }
) : ProfileComponent, AppComponentContext by context {

    private val chatListRepository: ChatListRepository = container.repositories.chatListRepository
    private val chatOperationsRepository: ChatOperationsRepository = container.repositories.chatOperationsRepository
    private val chatSettingsRepository: ChatSettingsRepository = container.repositories.chatSettingsRepository
    private val userRepository: UserRepository = container.repositories.userRepository
    private val userProfileSnapshotRepository: UserProfileSnapshotRepository =
        container.repositories.userProfileSnapshotRepository
    private val telegramBackendModeRepository = container.repositories.telegramBackendModeRepository
    private val profilePhotoRepository: ProfilePhotoRepository = container.repositories.profilePhotoRepository
    private val chatInfoRepository: ChatInfoRepository = container.repositories.chatInfoRepository
    private val botRepository: BotRepository = container.repositories.botRepository
    private val chatStatisticsRepository: ChatStatisticsRepository = container.repositories.chatStatisticsRepository
    private val privacyRepository: PrivacyRepository = container.repositories.privacyRepository
    override val messageRepository: MessageRepository = container.repositories.messageRepository
    private val locationRepository: LocationRepository = container.repositories.locationRepository
    private val gifRepository: GifRepository = container.repositories.gifRepository
    private val storyRepository: StoryRepository = container.repositories.storyRepository
    private val telegramLinkRepository: TelegramLinkRepository =
        container.repositories.telegramLinkRepository
    private val botPreferences: BotPreferencesProvider = container.preferences.botPreferencesProvider
    private val stringProvider = container.utils.stringProvider()
    private val messageDisplayer = container.utils.messageDisplayer()
    override val downloadUtils: IDownloadUtils = container.utils.downloadUtils()

    private val scope = componentScope
    private val _state = MutableValue(ProfileComponent.State(chatId = chatId))
    override val state: Value<ProfileComponent.State> = _state

    private val INITIAL_MEDIA_PAGE_SIZE = 21
    private val MEDIA_PAGE_SIZE = 50
    private val PROFILE_PHOTOS_LIMIT = 50
    private var hasStartedProfilePhotoListPreload = false
    private val loadingTabs = mutableSetOf<ProfileTabKey>()
    private var profileStoriesJob: Job? = null
    private var memberSearchJob: Job? = null

    init {
        loadData()
        observeProfilePhotos()
        observeUserUpdates()
        observeCurrentUser()
    }

    private fun loadData() {
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val chat = coRunCatching { chatListRepository.getChatById(chatId) }.getOrNull()
                val user = if (chat == null || (!chat.isGroup && !chat.isChannel)) {
                    loadProfileUser(chatId)
                } else null
                val isBlocked = if (user != null) {
                    privacyRepository.getBlockedUsers().contains(user.id)
                } else {
                    chat?.blockList == true
                }
                val fullInfo = coRunCatching { chatInfoRepository.getChatFullInfo(chatId) }.getOrNull()
                val description = fullInfo?.description
                val publicUsername = chat?.username?.takeIf { it.isNotBlank() }
                    ?: user?.username?.takeIf { it.isNotBlank() }
                val link = when {
                    publicUsername != null -> telegramLinkRepository.buildUrl(publicUsername)
                    else -> fullInfo?.inviteLink
                }

                var botWebAppUrl: String? = null
                var botWebAppName: String? = null
                var isTOSAccepted = false

                if (user?.type == UserTypeEnum.BOT) {
                    val botInfo = botRepository.getBotInfo(chatId)
                    val menuButton = botInfo?.menuButton
                    if (menuButton is BotMenuButtonModel.WebApp) {
                        botWebAppUrl = menuButton.url
                        botWebAppName = menuButton.text
                    }
                    isTOSAccepted = botPreferences.getWebappPermission(user.id, "tos_accepted")
                }

                val linkedChatId = fullInfo?.linkedChatId?.takeIf { it != 0L }
                val linkedChat = linkedChatId?.let {
                    coRunCatching { chatListRepository.getChatById(it) }.getOrNull()
                }
                val currentSimilarChats = _state.value.similarChats
                val isGroupOrChannel = chat?.let { it.isGroup || it.isChannel } ?: (chatId < 0)
                val preferredTabKey = fullInfo?.mainProfileTab.toProfileTabKeyOrNull()
                val resolvedMemberCount = (chat?.memberCount ?: 0).takeIf { it > 0 }
                    ?: fullInfo?.memberCount
                    ?: 0
                val supportedTabs = buildProfileTabSpecs(
                    isGroupOrChannel = isGroupOrChannel,
                    preferredTabKey = preferredTabKey,
                    showMembers = shouldShowMembersTab(
                        chat = chat,
                        fullInfo = fullInfo,
                        resolvedMemberCount = resolvedMemberCount
                    ),
                    showAdministrators = shouldShowAdminsTab(
                        chat = chat,
                        fullInfo = fullInfo
                    ),
                    showRestricted = shouldShowModerationTab(
                        chat = chat,
                        memberCount = fullInfo?.restrictedCount ?: 0
                    ),
                    showBanned = shouldShowModerationTab(
                        chat = chat,
                        memberCount = fullInfo?.bannedCount ?: 0
                    ),
                    showSimilarChats = currentSimilarChats.isNotEmpty()
                )
                val visibleTabs = buildInitialVisibleProfileTabSpecs(
                    supportedTabs = supportedTabs,
                    preferredTabKey = preferredTabKey
                )
                val currentSelectedTabKey = _state.value.selectedTabKey
                val selectedTabKey =
                    if (visibleTabs.any { it.key == currentSelectedTabKey }) {
                        currentSelectedTabKey
                    } else {
                        visibleTabs.firstOrNull { it.initiallySelected }?.key
                            ?: ProfileTabKey.STORIES
                    }

                _state.update {
                    it.copy(
                        chat = chat,
                        user = user,
                        isBlocked = isBlocked,
                        fullInfo = fullInfo,
                        about = description,
                        publicLink = link,
                        botWebAppUrl = botWebAppUrl,
                        botWebAppName = botWebAppName,
                        qrContent = link ?: "",
                        personalAvatarPath = user?.personalAvatarPath,
                        linkedChat = linkedChat,
                        isTOSAccepted = isTOSAccepted,
                        visibleTabs = visibleTabs,
                        selectedTabKey = selectedTabKey
                    )
                }

                refreshProfileStories()

                ensureTabLoaded(selectedTabKey)
                probeHiddenMediaTabs()
                probeSimilarChatsTab()

                preloadProfilePhotoList(
                    resolvedChatId = chat?.id ?: chatId,
                    resolvedUserId = user?.id?.takeIf { it > 0 },
                    isGroupOrChannel = chat?.let { it.isGroup || it.isChannel } ?: (chatId < 0)
                )
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    private suspend fun loadProfileUser(userId: Long): UserModel? =
        if (telegramBackendModeRepository.backendMode.value == TelegramBackendMode.KOTLIN_MTPROTO) {
            userProfileSnapshotRepository.getUser(DEFAULT_ACCOUNT_ID, userId)?.toUserModel()
        } else {
            userRepository.getUser(userId)
        }

    private fun UserProfileSnapshotModel.toUserModel() = UserModel(
        id = userId,
        firstName = firstName.orEmpty(),
        lastName = lastName,
        username = username,
        phoneNumber = phoneNumber,
        isPremium = isPremium,
        isVerified = isVerified,
        isScam = isScam,
        isFake = isFake,
        isContact = isContact,
        isMutualContact = isMutualContact,
        type = when {
            isDeleted -> UserTypeEnum.DELETED
            isBot -> UserTypeEnum.BOT
            else -> UserTypeEnum.REGULAR
        },
    )

    private fun observeCurrentUser() {
        userRepository.currentUserFlow
            .onEach { user ->
                _state.update { it.copy(currentUser = user) }
                refreshProfileStories()
            }
            .launchIn(scope)

        storyRepository.activeStories
            .onEach { activeStories ->
                val activeStoryList = activeStories.values
                    .asSequence()
                    .flatMap { lists -> lists.asSequence() }
                    .firstOrNull { list -> list.chatId == chatId }
                val activeStoryPreviews = resolveActiveStoryPreviews(activeStoryList)
                _state.update {
                    it.copy(
                        activeStoryList = activeStoryList,
                        activeStories = activeStoryPreviews
                    )
                }
            }
            .launchIn(scope)
    }

    private fun refreshProfileStories() {
        profileStoriesJob?.cancel()
        profileStoriesJob = scope.launch {
            _state.update { it.copy(isStoriesLoading = true) }
            try {
                val snapshot = _state.value
                val postedStoriesHint = snapshot.fullInfo?.hasPinnedStories == true ||
                        snapshot.fullInfo?.hasPostedToProfileStories == true

                val activeStoryList = coRunCatching {
                    storyRepository.getChatActiveStories(chatId)
                }.getOrNull()
                val activeStoryPreviews =
                    resolveActiveStoryPreviews(activeStoryList ?: snapshot.activeStoryList)

                val shouldLoadPostedStories = postedStoriesHint || isCurrentUserProfile(snapshot)
                val postedStoriesPage = if (shouldLoadPostedStories) {
                    coRunCatching {
                        storyRepository.getChatPostedToChatPageStories(
                            chatId = chatId,
                            limit = PROFILE_POSTED_STORIES_LIMIT
                        )
                    }.getOrNull()
                } else {
                    null
                }

                _state.update { current ->
                    current.copy(
                        activeStoryList = activeStoryList ?: current.activeStoryList,
                        activeStories = when {
                            (activeStoryList
                                ?: current.activeStoryList)?.stories.isNullOrEmpty() -> emptyList()

                            activeStoryPreviews.isNotEmpty() -> activeStoryPreviews
                            else -> current.activeStories
                        },
                        postedStories = when {
                            postedStoriesPage != null -> postedStoriesPage.stories
                            shouldLoadPostedStories -> current.postedStories
                            else -> emptyList()
                        },
                        postedStoryCount = postedStoriesPage?.totalCount
                            ?: current.postedStoryCount,
                        hasPostedStoriesHint = postedStoriesHint ||
                                (postedStoriesPage?.totalCount ?: 0) > 0
                    )
                }
            } finally {
                _state.update { it.copy(isStoriesLoading = false) }
            }
        }
    }

    private suspend fun resolveActiveStoryPreviews(
        activeStoryList: org.monogram.domain.models.stories.ActiveStoryListModel?
    ): List<org.monogram.domain.models.stories.StoryModel> {
        val summaries = activeStoryList?.stories.orEmpty().take(PROFILE_ACTIVE_STORIES_LIMIT)
        if (summaries.isEmpty()) return emptyList()

        val existingById = _state.value.activeStories.associateBy { it.id }
        return summaries.mapNotNull { summary ->
            coRunCatching {
                storyRepository.getStory(chatId = chatId, storyId = summary.storyId)
            }.getOrNull()
                ?.copy(isRead = summary.isRead)
                ?: existingById[summary.storyId]?.copy(isRead = summary.isRead)
        }
    }

    override fun onLoadMoreMedia() {
        loadNextPage(_state.value.selectedTabKey)
    }

    override fun onDownloadMedia(message: MessageModel) {
        scope.launch {

            val highResId = if (message.content is MessageContent.Photo) {
                messageRepository.getHighResFileId(chatId, message.id)
            } else null
            val fileId = if (highResId != null && highResId != 0) {
                highResId
            } else {
                when (val content = message.content) {
                    is MessageContent.Photo -> content.fileId
                    is MessageContent.Video -> content.fileId
                    else -> 0
                }
            }

            if (fileId != 0) {
                messageRepository.downloadFile(fileId, priority = 16)
                val downloadedPath = awaitDownloadedPath(fileId) ?: return@launch
                withContext(Dispatchers.Main) {
                    onFileDownloaded(fileId, downloadedPath)
                }
            }
        }
    }

    fun onFileDownloaded(fileId: Int, newPath: String) {
        if (fileId == 0) return

        val currentState = _state.value
        val updatedMessageTabs = currentState.messageTabs.mapValues { (_, tabState) ->
            tabState.copy(
                items = tabState.items.map { msg ->
                    updateMessagePathIfNeeded(msg, fileId, newPath)
                }
            )
        }

        if (updatedMessageTabs != currentState.messageTabs) {
            _state.update {
                var nextState = it.copy(messageTabs = updatedMessageTabs)

                if (it.fullScreenImages != null && !it.isViewingProfilePhotos) {
                    nextState = refreshViewerMedia(nextState)
                }
                nextState
            }
        }
    }

    private fun updateMessagePathIfNeeded(msg: MessageModel, targetFileId: Int, newPath: String): MessageModel {
        val content = msg.content
        val shouldUpdate = when (content) {
            is MessageContent.Photo -> content.fileId == targetFileId
            is MessageContent.Video -> content.fileId == targetFileId
            is MessageContent.Document -> content.fileId == targetFileId
            else -> false
        }

        return if (shouldUpdate) {
            val newContent = when (content) {
                is MessageContent.Photo -> content.copy(path = newPath)
                is MessageContent.Video -> content.copy(path = newPath)
                is MessageContent.Document -> content.copy(path = newPath)
                else -> content
            }
            msg.copy(content = newContent)
        } else {
            msg
        }
    }

    private fun MessageContent.Photo.displayPath(): String? = path ?: thumbnailPath

    private fun ensureTabLoaded(tabKey: ProfileTabKey) {
        when (tabKey) {
            ProfileTabKey.STORIES -> Unit
            ProfileTabKey.SIMILAR -> {
                if (!_state.value.hasLoadedSimilarChats) {
                    loadSimilarChats()
                }
            }
            else -> {
                if (tabKey.isMemberTab()) {
                    if (!_state.value.memberTabState(tabKey).hasLoaded) {
                        loadMembersNextPage(tabKey)
                    }
                    return
                }
                if (!_state.value.messageTabState(tabKey).hasLoaded) {
                    loadMessageTabNextPage(tabKey)
                }
            }
        }
    }

    private fun loadNextPage(tabKey: ProfileTabKey) {
        when (tabKey) {
            ProfileTabKey.STORIES -> Unit
            ProfileTabKey.SIMILAR -> Unit
            else -> if (tabKey.isMemberTab()) {
                loadMembersNextPage(tabKey)
            } else {
                loadMessageTabNextPage(tabKey)
            }
        }
    }

    private fun loadMembersNextPage(tabKey: ProfileTabKey) {
        val filter = tabKey.toChatMembersFilterOrNull() ?: return
        val currentTab = _state.value.memberTabState(tabKey)
        if (!loadingTabs.add(tabKey)) return
        if (
            currentTab.isLoadingInitial ||
            currentTab.isLoadingNext ||
            currentTab.isSearchResultsVisible ||
            currentTab.isSearching ||
            (currentTab.hasLoaded && !currentTab.canLoadMore)
        ) {
            loadingTabs.remove(tabKey)
            return
        }

        scope.launch {
            val isInitialLoad = !currentTab.hasLoaded
            _state.update {
                it.copy(
                    memberTabs = it.memberTabs + (
                            tabKey to it.memberTabState(tabKey).copy(
                                isLoadingInitial = isInitialLoad,
                                isLoadingNext = !isInitialLoad
                            )
                    )
                )
            }
            try {
                val limit = 20
                val newMembers =
                    chatInfoRepository.getChatMembers(
                        chatId,
                        currentTab.nextOffset,
                        limit,
                        filter
                    )
                val canLoadMore = newMembers.size >= limit

                _state.update {
                    val latestTab = it.memberTabState(tabKey)
                    val mergedMembers =
                        (latestTab.items + newMembers).distinctBy { member -> member.user.id }
                    it.copy(
                        memberTabs = it.memberTabs + (
                                tabKey to latestTab.copy(
                                    items = mergedMembers,
                                    canLoadMore = canLoadMore,
                                    nextOffset = latestTab.nextOffset + newMembers.size,
                                    hasLoaded = true
                                )
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                loadingTabs.remove(tabKey)
                _state.update {
                    it.copy(
                        memberTabs = it.memberTabs + (
                                tabKey to it.memberTabState(tabKey).copy(
                                    isLoadingInitial = false,
                                    isLoadingNext = false
                                )
                        )
                    )
                }
            }
        }
    }

    private fun loadMessageTabNextPage(tabKey: ProfileTabKey) {
        val filter = tabKey.toProfileMediaFilter() ?: return
        val currentTab = _state.value.messageTabState(tabKey)
        if (!loadingTabs.add(tabKey)) return
        if (currentTab.isLoadingInitial || currentTab.isLoadingNext || (currentTab.hasLoaded && !currentTab.canLoadMore)) {
            loadingTabs.remove(tabKey)
            return
        }

        scope.launch {
            val isInitialLoad = !currentTab.hasLoaded
            _state.update {
                it.updateMessageTab(tabKey) { tab ->
                    tab.copy(
                        isLoadingInitial = isInitialLoad,
                        isLoadingNext = !isInitialLoad
                    )
                }
            }

            try {
                val pageLimit = if (isInitialLoad) INITIAL_MEDIA_PAGE_SIZE else MEDIA_PAGE_SIZE
                val messages = messageRepository.getProfileMedia(
                    chatId = chatId,
                    filter = filter,
                    fromMessageId = if (isInitialLoad) 0L else currentTab.nextFromMessageId,
                    limit = pageLimit
                )
                val canLoadMore = messages.size >= pageLimit

                _state.update { state ->
                    var nextState = state.updateMessageTab(tabKey) { tab ->
                        val mergedMessages =
                            (tab.items + messages).distinctBy { message -> message.id }
                        tab.copy(
                            items = mergedMessages,
                            canLoadMore = canLoadMore,
                            nextFromMessageId = mergedMessages.lastOrNull()?.id
                                ?: tab.nextFromMessageId,
                            hasLoaded = true
                        )
                    }
                    nextState = nextState.updateMediaTabVisibility(tabKey)

                    if (tabKey == ProfileTabKey.MEDIA && nextState.fullScreenImages != null && !nextState.isViewingProfilePhotos) {
                        nextState = refreshViewerMedia(nextState)
                    }

                    nextState
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                loadingTabs.remove(tabKey)
                _state.update {
                    it.updateMessageTab(tabKey) { tab ->
                        tab.copy(
                            isLoadingInitial = false,
                            isLoadingNext = false
                        )
                    }
                }
            }
        }
    }

    private fun refreshViewerMedia(state: ProfileComponent.State): ProfileComponent.State {
        val allPhotos = state.mediaMessages.filter { it.content is MessageContent.Photo }
        val paths = allPhotos.mapNotNull { (it.content as? MessageContent.Photo)?.displayPath() }
        val captions = allPhotos.map { (it.content as? MessageContent.Photo)?.caption }
        return state.copy(
            fullScreenImages = paths,
            fullScreenCaptions = captions
        )
    }

    private fun ProfileComponent.State.updateMessageTab(
        key: ProfileTabKey,
        transform: (ProfileComponent.MessageTabState) -> ProfileComponent.MessageTabState
    ): ProfileComponent.State {
        val currentTab = messageTabState(key)
        return copy(messageTabs = messageTabs + (key to transform(currentTab)))
    }

    private fun ProfileComponent.State.updateMemberTab(
        key: ProfileTabKey,
        transform: (ProfileComponent.MembersTabState) -> ProfileComponent.MembersTabState
    ): ProfileComponent.State {
        val currentTab = memberTabState(key)
        return copy(memberTabs = memberTabs + (key to transform(currentTab)))
    }

    private fun ProfileComponent.State.supportedTabSpecs(): List<ProfileTabSpec> {
        val chat = chat
        val fullInfo = fullInfo
        val isGroupOrChannel = chat?.let { it.isGroup || it.isChannel } ?: (chatId < 0)
        val resolvedMemberCount = (chat?.memberCount ?: 0).takeIf { it > 0 }
            ?: fullInfo?.memberCount
            ?: 0
        return buildProfileTabSpecs(
            isGroupOrChannel = isGroupOrChannel,
            preferredTabKey = fullInfo?.mainProfileTab.toProfileTabKeyOrNull(),
            showMembers = shouldShowMembersTab(
                chat = chat,
                fullInfo = fullInfo,
                resolvedMemberCount = resolvedMemberCount
            ),
            showAdministrators = shouldShowAdminsTab(
                chat = chat,
                fullInfo = fullInfo
            ),
            showRestricted = shouldShowModerationTab(
                chat = chat,
                memberCount = fullInfo?.restrictedCount ?: 0
            ),
            showBanned = shouldShowModerationTab(
                chat = chat,
                memberCount = fullInfo?.bannedCount ?: 0
            ),
            showSimilarChats = similarChats.isNotEmpty()
        )
    }

    private fun ProfileComponent.State.ensureVisibleTab(tabKey: ProfileTabKey): ProfileComponent.State {
        if (visibleTabs.any { it.key == tabKey }) return this
        val visibleKeys = visibleTabs.map(ProfileTabSpec::key).toMutableSet().apply {
            add(tabKey)
        }
        return copy(visibleTabs = supportedTabSpecs().filter { it.key in visibleKeys })
    }

    private fun ProfileComponent.State.removeVisibleTab(tabKey: ProfileTabKey): ProfileComponent.State {
        if (selectedTabKey == tabKey || visibleTabs.none { it.key == tabKey }) return this
        val visibleKeys = visibleTabs
            .map(ProfileTabSpec::key)
            .filterNot { it == tabKey }
            .toSet()
        return copy(visibleTabs = supportedTabSpecs().filter { it.key in visibleKeys })
    }

    private fun ProfileComponent.State.updateMediaTabVisibility(tabKey: ProfileTabKey): ProfileComponent.State {
        if (!tabKey.isMediaTab()) return this
        val hasContent = messageTabState(tabKey).items.isNotEmpty()
        if (hasContent) {
            var nextState = ensureVisibleTab(tabKey)
            val mediaTabState = nextState.messageTabState(ProfileTabKey.MEDIA)
            if (
                tabKey != ProfileTabKey.MEDIA &&
                nextState.selectedTabKey != ProfileTabKey.MEDIA &&
                nextState.visibleTabs.any { it.key == ProfileTabKey.MEDIA } &&
                mediaTabState.hasLoaded &&
                mediaTabState.items.isEmpty()
            ) {
                nextState = nextState.removeVisibleTab(ProfileTabKey.MEDIA)
            }
            return nextState
        }
        if (selectedTabKey == tabKey) {
            return this
        }
        val visibleMediaCount = visibleTabs.count { it.key.isMediaTab() }
        if (tabKey == ProfileTabKey.MEDIA && visibleMediaCount <= 1) {
            return this
        }
        return removeVisibleTab(tabKey)
    }

    private fun probeHiddenMediaTabs() {
        val snapshot = _state.value
        val visibleKeys = snapshot.visibleTabs.map(ProfileTabSpec::key).toSet()
        snapshot.supportedTabSpecs()
            .asSequence()
            .map(ProfileTabSpec::key)
            .filter(ProfileTabKey::isMediaTab)
            .filterNot { it in visibleKeys }
            .forEach(::loadMessageTabNextPage)
    }

    private fun probeSimilarChatsTab() {
        val snapshot = _state.value
        val chat = snapshot.chat ?: return
        if (!chat.isChannel) return
        if (snapshot.hasLoadedSimilarChats || snapshot.isSimilarChatsLoading) return
        loadSimilarChats()
    }

    private fun loadSimilarChats() {
        if (!loadingTabs.add(ProfileTabKey.SIMILAR)) return
        val snapshot = _state.value
        if (snapshot.isSimilarChatsLoading || snapshot.hasLoadedSimilarChats) {
            loadingTabs.remove(ProfileTabKey.SIMILAR)
            return
        }

        scope.launch {
            _state.update { it.copy(isSimilarChatsLoading = true) }
            try {
                val similarChats = chatInfoRepository.getSimilarChatIds(chatId)
                    .mapNotNull { relatedChatId ->
                        coRunCatching { chatListRepository.getChatById(relatedChatId) }.getOrNull()
                    }
                    .distinctBy(ChatModel::id)

                _state.update { state ->
                    var nextState = state.copy(
                        similarChats = similarChats,
                        isSimilarChatsLoading = false,
                        hasLoadedSimilarChats = true
                    )
                    nextState = if (similarChats.isNotEmpty()) {
                        nextState.ensureVisibleTab(ProfileTabKey.SIMILAR)
                    } else {
                        nextState.removeVisibleTab(ProfileTabKey.SIMILAR)
                    }
                    nextState
                }
            } finally {
                loadingTabs.remove(ProfileTabKey.SIMILAR)
                _state.update { it.copy(isSimilarChatsLoading = false) }
            }
        }
    }

    override fun onTabSelected(tabKey: ProfileTabKey) {
        if (_state.value.selectedTabKey == tabKey) return
        if (_state.value.visibleTabs.none { it.key == tabKey }) return

        _state.update { it.copy(selectedTabKey = tabKey) }
        ensureTabLoaded(tabKey)
    }

    override fun onSearch() {
        val tabKey = _state.value.selectedTabKey
        if (!tabKey.isMemberTab()) return

        val isActive = _state.value.memberTabState(tabKey).isSearchActive
        if (isActive) {
            onSearchDismissed()
            return
        }

        _state.update {
            it.updateMemberTab(tabKey) { tab ->
                tab.copy(isSearchActive = true)
            }
        }
    }

    override fun onSearchQueryChanged(query: String) {
        val tabKey = _state.value.selectedTabKey
        if (!tabKey.isMemberTab()) return

        memberSearchJob?.cancel()
        _state.update {
            it.updateMemberTab(tabKey) { tab ->
                tab.copy(
                    searchQuery = query,
                    searchResults = if (query.isBlank()) emptyList() else tab.searchResults,
                    isSearching = query.isNotBlank()
                )
            }
        }

        if (query.isBlank()) return

        memberSearchJob = scope.launch {
            delay(300)
            try {
                val results = chatInfoRepository.getChatMembers(
                    chatId = chatId,
                    offset = 0,
                    limit = 50,
                    filter = ChatMembersFilter.Search(query)
                )
                val filteredResults = filterMemberSearchResults(tabKey, results)
                _state.update { state ->
                    state.updateMemberTab(tabKey) { tab ->
                        if (tab.searchQuery != query) {
                            tab
                        } else {
                            tab.copy(
                                searchResults = filteredResults,
                                isSearching = false
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _state.update { state ->
                    state.updateMemberTab(tabKey) { tab ->
                        if (tab.searchQuery != query) {
                            tab
                        } else {
                            tab.copy(isSearching = false)
                        }
                    }
                }
            }
        }
    }

    override fun onSearchDismissed() {
        val tabKey = _state.value.selectedTabKey
        if (!tabKey.isMemberTab()) return

        memberSearchJob?.cancel()
        _state.update {
            it.updateMemberTab(tabKey) { tab ->
                tab.copy(
                    searchQuery = "",
                    searchResults = emptyList(),
                    isSearchActive = false,
                    isSearching = false
                )
            }
        }
    }

    private fun filterMemberSearchResults(
        tabKey: ProfileTabKey,
        members: List<org.monogram.domain.models.GroupMemberModel>
    ): List<org.monogram.domain.models.GroupMemberModel> =
        when (tabKey) {
            ProfileTabKey.ADMINS -> members.filter {
                it.status is ChatMemberStatus.Administrator || it.status is ChatMemberStatus.Creator
            }

            ProfileTabKey.RESTRICTED -> members.filter { it.status is ChatMemberStatus.Restricted }
            ProfileTabKey.BANNED -> members.filter { it.status is ChatMemberStatus.Banned }
            ProfileTabKey.MEMBERS -> members.filter { it.status is ChatMemberStatus.Member }
            else -> members
        }

    private fun observeUserUpdates() {
        userRepository.getUserFlow(chatId)
            .onEach { user ->
                if (user != null) {
                    _state.update { it.copy(user = user, personalAvatarPath = user.personalAvatarPath) }
                }
            }
            .launchIn(scope)
    }

    private fun observeProfilePhotos() {
        val profilePhotosFlow = if (isGroupOrChannelProfile()) {
            profilePhotoRepository.getChatProfilePhotosFlow(chatId)
        } else {
            profilePhotoRepository.getUserProfilePhotosFlow(chatId)
        }

        profilePhotosFlow
            .onEach { photos ->
                if (photos.isNotEmpty()) {
                    _state.update { it.copy(profilePhotos = photos) }
                }
            }
            .launchIn(scope)
    }

    private fun preloadProfilePhotoList(
        resolvedChatId: Long,
        resolvedUserId: Long?,
        isGroupOrChannel: Boolean
    ) {
        if (hasStartedProfilePhotoListPreload) return
        hasStartedProfilePhotoListPreload = true

        scope.launch {
            val preloadedPhotos = if (isGroupOrChannel) {
                coRunCatching {
                    profilePhotoRepository.getChatProfilePhotos(
                        chatId = resolvedChatId,
                        offset = 0,
                        limit = PROFILE_PHOTOS_LIMIT
                    )
                }.getOrDefault(emptyList())
            } else {
                val userId = resolvedUserId ?: resolvedChatId.takeIf { it > 0 } ?: return@launch
                coRunCatching {
                    profilePhotoRepository.getUserProfilePhotos(
                        userId = userId,
                        offset = 0,
                        limit = PROFILE_PHOTOS_LIMIT
                    )
                }.getOrDefault(emptyList())
            }

            if (preloadedPhotos.isNotEmpty()) {
                _state.update { current ->
                    if (current.profilePhotos.isEmpty()) {
                        current.copy(profilePhotos = preloadedPhotos)
                    } else {
                        current
                    }
                }
            }
        }
    }

    override fun onBack() {
        val selectedTabKey = _state.value.selectedTabKey
        if (selectedTabKey.isMemberTab() && _state.value.memberTabState(selectedTabKey).isSearchActive) {
            onSearchDismissed()
            return
        }
        onBackClicked()
    }

    override fun onMessageClick(message: MessageModel) {
        when (val content = message.content) {
            is MessageContent.Photo -> {
                scope.launch {
                    val bigFileId = messageRepository.getHighResFileId(chatId, message.id)
                    if (bigFileId != null && bigFileId != 0) {
                        updatePhotoOriginalFileId(message.id, bigFileId)
                    }
                }

                val viewerItems = _state.value.mediaMessages
                    .asSequence()
                    .filter { it.content is MessageContent.Photo }
                    .mapNotNull {
                        val photo = it.content as? MessageContent.Photo ?: return@mapNotNull null
                        val displayPath = photo.displayPath() ?: return@mapNotNull null
                        Triple(it.id, displayPath, photo.caption)
                    }
                    .toList()

                if (viewerItems.isNotEmpty()) {
                    val startIndex = viewerItems.indexOfFirst { it.first == message.id }
                        .takeIf { it != -1 } ?: 0

                    _state.update {
                        it.copy(
                            fullScreenImages = viewerItems.map { item -> item.second },
                            fullScreenCaptions = viewerItems.map { item -> item.third },
                            fullScreenStartIndex = startIndex,
                            isViewingProfilePhotos = false
                        )
                    }
                }
            }
            is MessageContent.Video -> {
                content.path?.let { path ->
                    _state.update {
                        it.copy(
                            fullScreenVideoPath = path,
                            fullScreenVideoCaption = content.caption
                        )
                    }
                } ?: run {
                    if (content.fileId != 0) {
                        scope.launch {
                            messageRepository.downloadFile(content.fileId, priority = 32)
                            val downloadedPath = awaitDownloadedPath(content.fileId) ?: return@launch
                            withContext(Dispatchers.Main) {
                                _state.update {
                                    it.copy(
                                        fullScreenVideoPath = downloadedPath,
                                        fullScreenVideoCaption = content.caption
                                    )
                                }
                                onFileDownloaded(content.fileId, downloadedPath)
                            }
                        }
                    }
                }
            }
            is MessageContent.Gif -> {
                content.path?.let { path ->
                    _state.update {
                        it.copy(
                            fullScreenVideoPath = path,
                            fullScreenVideoCaption = content.caption
                        )
                    }
                }
            }
            is MessageContent.VideoNote -> {
                content.path?.let { path ->
                    _state.update {
                        it.copy(
                            fullScreenVideoPath = path,
                            fullScreenVideoCaption = null
                        )
                    }
                }
            }
            is MessageContent.Location -> {
                onLocationClick(content.latitude, content.longitude, stringProvider.getString("location_label"))
            }

            is MessageContent.Venue -> {
                onLocationClick(content.latitude, content.longitude, content.title)
            }
            else -> onMessageClicked(message)
        }
    }

    override fun onMessageLongClick(message: MessageModel) {
        onMessageLongClicked(message)
    }

    override fun onAvatarClick() {
        val snapshot = _state.value
        val initialPhotos = snapshot.profilePhotos

        val availablePhotos = initialPhotos.filter { it.displayPath != null }
        if (availablePhotos.isNotEmpty()) {
            openProfilePhotos(availablePhotos)
        } else {
            val avatarPath = snapshot.user?.avatarPath?.takeIf { it.isNotBlank() }
                ?: snapshot.chat?.avatarPath?.takeIf { it.isNotBlank() }
                ?: snapshot.personalAvatarPath?.takeIf { it.isNotBlank() }
                ?: snapshot.chat?.personalAvatarPath?.takeIf { it.isNotBlank() }
            avatarPath?.let {
                openProfilePhotos(
                    listOf(
                        ProfilePhotoMedia(
                            id = it.hashCode().toLong(),
                            previewPath = it,
                            originalFileId = 0
                        )
                    )
                )
            }
        }

        val isGroupOrChannel = isGroupOrChannelProfile(snapshot)

        scope.launch {
            _state.update { it.copy(isProfilePhotoHdLoading = true) }
            try {
                val refreshedPhotos = if (isGroupOrChannel) {
                    coRunCatching {
                        profilePhotoRepository.getChatProfilePhotos(
                            chatId = snapshot.chatId,
                            offset = 0,
                            limit = PROFILE_PHOTOS_LIMIT
                        )
                    }.getOrDefault(emptyList())
                } else {
                    val userId = snapshot.user?.id?.takeIf { it > 0 } ?: snapshot.chatId.takeIf { it > 0 }
                    if (userId == null) return@launch
                    coRunCatching {
                        profilePhotoRepository.getUserProfilePhotos(
                            userId = userId,
                            offset = 0,
                            limit = PROFILE_PHOTOS_LIMIT
                        )
                    }.getOrDefault(emptyList())
                }

                if (refreshedPhotos.isEmpty()) return@launch

                _state.update { current ->
                    val next = current.copy(profilePhotos = refreshedPhotos)
                    val viewerIsOpen = current.fullScreenImages != null || current.fullScreenVideoPath != null
                    if (!viewerIsOpen) {
                        next
                    } else {
                        applyProfilePhotosToViewer(next, refreshedPhotos)
                    }
                }
            } finally {
                _state.update { it.copy(isProfilePhotoHdLoading = false) }
            }
        }
    }

    private fun openProfilePhotos(photos: List<ProfilePhotoMedia>) {
        if (photos.isEmpty()) return
        _state.update { current ->
            applyProfilePhotosToViewer(current, photos)
        }
    }

    private fun applyProfilePhotosToViewer(
        state: ProfileComponent.State,
        photos: List<ProfilePhotoMedia>
    ): ProfileComponent.State {
        val firstPhoto = photos.firstOrNull() ?: return state
        val firstPath = firstPhoto.displayPath ?: return state
        if (firstPhoto.animationPath != null || firstPath.endsWith(".mp4", ignoreCase = true)) {
            return state.copy(
                fullScreenVideoPath = firstPhoto.animationPath ?: firstPath,
                fullScreenVideoCaption = null,
                fullScreenImages = null,
                fullScreenCaptions = emptyList(),
                fullScreenStartIndex = 0,
                isViewingProfilePhotos = true
            )
        }

        val images = photos.mapIndexedNotNull { index, media ->
            media.previewPath ?: media.originalPath ?: state.fullScreenImages?.getOrNull(index)
        }
        if (images.isEmpty()) return state

        val safeIndex = state.fullScreenStartIndex.coerceIn(0, images.lastIndex)
        return state.copy(
            fullScreenImages = images,
            fullScreenCaptions = images.map { null },
            fullScreenStartIndex = safeIndex,
            fullScreenVideoPath = null,
            fullScreenVideoCaption = null,
            isViewingProfilePhotos = true
        )
    }

    override fun onDismissViewer() {
        _state.update {
            it.copy(
                fullScreenImages = null,
                fullScreenImageMessageIds = emptyList(),
                fullScreenCaptions = emptyList(),
                fullScreenVideoPath = null,
                fullScreenVideoMessageId = null,
                fullScreenVideoCaption = null,
                isViewingProfilePhotos = false,
                isProfilePhotoHdLoading = false
            )
        }
    }

    override fun onDismissImages() {
        onDismissViewer()
    }

    override fun onDismissVideo() {
        onDismissViewer()
    }

    override fun onDismissInstantView() {
        _state.update { it.copy(instantViewUrl = null) }
    }

    override fun onDismissYouTube() {
        _state.update { it.copy(youtubeUrl = null) }
    }

    override fun onDismissWebView() {
        _state.update { it.copy(webViewUrl = null) }
    }

    override fun onDismissInvoice(status: String?) {
        _state.update { it.copy(invoiceSlug = null, invoiceMessageId = null) }
    }

    override fun onForwardMessage(message: MessageModel) {
        onMessageLongClicked(message)
    }

    override fun onDeleteMessage(message: MessageModel, revoke: Boolean) {
        scope.launch {
            messageRepository.deleteMessage(chatId, listOf(message.id), revoke)
        }
    }

    override fun onOpenVideo(path: String, messageId: Long?, caption: String?) {
        _state.update {
            it.copy(
                fullScreenVideoPath = path,
                fullScreenVideoMessageId = messageId,
                fullScreenVideoCaption = caption,
                fullScreenImages = null
            )
        }
    }

    override fun onDownloadHighRes(messageId: Long) {
        scope.launch {
            val fileId = messageRepository.getHighResFileId(chatId, messageId)
            if (fileId != null && fileId != 0) {
                updatePhotoOriginalFileId(messageId, fileId)
            }
        }
    }

    private fun updatePhotoOriginalFileId(messageId: Long, fileId: Int) {
        _state.update { state ->
            state.copy(
                messageTabs = state.messageTabs.mapValues { (_, tab) ->
                    tab.copy(
                        items = tab.items.map { message ->
                            if (message.id != messageId) return@map message
                            val photo =
                                message.content as? MessageContent.Photo ?: return@map message
                            message.copy(content = photo.copy(originalFileId = fileId))
                        }
                    )
                }
            )
        }
    }

    override fun onAddToGifs(path: String) {
        scope.launch {
            gifRepository.addSavedGif(path)
        }
    }

    override fun onOpenWebView(url: String) {
        _state.update { it.copy(webViewUrl = url) }
    }

    override fun onDismissMiniAppTOS() {
        _state.update { it.copy(showMiniAppTOS = false) }
    }

    override fun onAcceptMiniAppTOS() {
        val botId = _state.value.user?.id ?: return
        scope.launch {
            botPreferences.setWebappPermission(botId, "tos_accepted", true)
            _state.update { it.copy(showMiniAppTOS = false, isTOSAccepted = true) }
        }
    }

    override fun onOpenMiniApp(url: String, name: String, chatId: Long) {
        _state.update { it.copy(miniAppUrl = url, miniAppName = name, chatId = chatId) }
    }

    override fun onDismissMiniApp() {
        _state.update { it.copy(miniAppUrl = null, miniAppName = null) }
    }

    override fun onShareToStory(mediaUrl: String, text: String?, widgetLink: String?) {
        onShareToStoryRequested(mediaUrl, text, widgetLink)
    }

    override fun onToggleMute() {
        val chat = _state.value.chat ?: return
        val shouldMute = !chat.isMuted

        runAction(ChatActionType.Mute) {
            chatOperationsRepository.toggleMuteChats(setOf(chatId), shouldMute)
            updateChat(chatId)
        }
    }

    override fun onToggleJoinToSendMessages(enabled: Boolean) {
        val previousChat = _state.value.chat ?: return
        if (previousChat.joinToSendMessages == enabled) return

        performProfileSettingChange(
            optimisticUpdate = { state ->
                state.copy(chat = state.chat?.copy(joinToSendMessages = enabled))
            },
            rollbackUpdate = { state ->
                state.copy(chat = state.chat?.copy(joinToSendMessages = previousChat.joinToSendMessages))
            }
        ) {
            chatSettingsRepository.setChatJoinToSendMessages(chatId, enabled)
        }
    }

    override fun onToggleJoinByRequest(enabled: Boolean) {
        val previousChat = _state.value.chat ?: return
        if (!previousChat.isGroup || !previousChat.isSupergroup || previousChat.isChannel) return
        if (previousChat.joinByRequest == enabled) return

        performProfileSettingChange(
            optimisticUpdate = { state ->
                state.copy(chat = state.chat?.copy(joinByRequest = enabled))
            },
            rollbackUpdate = { state ->
                state.copy(chat = state.chat?.copy(joinByRequest = previousChat.joinByRequest))
            }
        ) {
            chatSettingsRepository.setChatJoinByRequest(chatId, enabled)
        }
    }

    override fun onToggleHiddenMembers(enabled: Boolean) {
        val previousFullInfo = _state.value.fullInfo ?: return
        if (!previousFullInfo.canHideMembers || previousFullInfo.hasHiddenMembers == enabled) return

        performProfileSettingChange(
            optimisticUpdate = { state ->
                state.copy(fullInfo = state.fullInfo?.copy(hasHiddenMembers = enabled))
            },
            rollbackUpdate = { state ->
                state.copy(fullInfo = state.fullInfo?.copy(hasHiddenMembers = previousFullInfo.hasHiddenMembers))
            }
        ) {
            chatSettingsRepository.setChatHasHiddenMembers(chatId, enabled)
        }
    }

    override fun onToggleAggressiveAntiSpam(enabled: Boolean) {
        val previousFullInfo = _state.value.fullInfo ?: return
        if (!previousFullInfo.canToggleAggressiveAntiSpam ||
            previousFullInfo.hasAggressiveAntiSpamEnabled == enabled
        ) {
            return
        }

        performProfileSettingChange(
            optimisticUpdate = { state ->
                state.copy(fullInfo = state.fullInfo?.copy(hasAggressiveAntiSpamEnabled = enabled))
            },
            rollbackUpdate = { state ->
                state.copy(
                    fullInfo = state.fullInfo?.copy(
                        hasAggressiveAntiSpamEnabled = previousFullInfo.hasAggressiveAntiSpamEnabled
                    )
                )
            }
        ) {
            chatSettingsRepository.setChatHasAggressiveAntiSpamEnabled(chatId, enabled)
        }
    }

    override fun onEdit() {
        onEditClicked()
    }

    override fun onShowQRCode() {
        scope.launch {
            val latestLink = resolveLatestPublicLink()
            _state.update {
                it.copy(
                    publicLink = latestLink ?: it.publicLink,
                    qrContent = latestLink ?: it.qrContent,
                    isQrVisible = true
                )
            }
        }
    }

    override fun onDismissQRCode() {
        _state.update { it.copy(isQrVisible = false) }
    }

    override fun onSendMessage() {
        onSendMessageClicked(chatId)
    }

    override fun onRelatedChatClick(chatId: Long) {
        onSendMessageClicked(chatId)
    }

    override fun onToggleBlockUser() {
        val userId = _state.value.user?.id ?: return
        val shouldBlock = !_state.value.isBlocked
        runAction(if (shouldBlock) ChatActionType.BlockUser else ChatActionType.UnblockUser) {
            if (shouldBlock) {
                privacyRepository.blockUser(userId)
            } else {
                privacyRepository.unblockUser(userId)
            }
            _state.update { it.copy(isBlocked = shouldBlock) }
            updateChat(chatId)
        }
    }

    override fun onDeleteChat() {
        runAction(ChatActionType.Delete, closeOnSuccess = true) {
            chatOperationsRepository.deleteChats(setOf(chatId))
        }
    }

    private suspend fun awaitDownloadedPath(fileId: Int, timeoutMs: Long = 20_000L): String? {
        if (fileId == 0) return null

        val fileInfo = messageRepository.getFileInfo(fileId)
        if (fileInfo?.local?.isDownloadingCompleted == true && fileInfo.local.path.isNotEmpty()) {
            return fileInfo.local.path
        }

        val completed = withTimeoutOrNull(timeoutMs) {
            messageRepository.fileDownloadFlow
                .filterIsInstance<FileDownloadEvent.Completed>()
                .first { event -> event.fileId == fileId && event.path.isNotEmpty() }
        }
        if (completed != null) {
            return completed.path
        }

        val fallback = messageRepository.getFileInfo(fileId)
        return fallback?.local?.path?.takeIf {
            fallback.local.isDownloadingCompleted && it.isNotEmpty()
        }
    }

    override fun onEditContact() {
        val userId = _state.value.user?.id ?: return
        onEditContactClicked(userId)
    }

    private fun isGroupOrChannelProfile(snapshot: ProfileComponent.State = _state.value): Boolean {
        val chat = snapshot.chat
        if (chat != null) return chat.isGroup || chat.isChannel
        return snapshot.chatId < 0
    }

    private fun isCurrentUserProfile(snapshot: ProfileComponent.State = _state.value): Boolean {
        val userId = snapshot.user?.id ?: return false
        return snapshot.currentUser?.id == userId
    }

    override fun onToggleContact() {
        val user = _state.value.user ?: return
        onEditContactClicked(user.id)
    }

    override fun onLeave() {
        runAction(ChatActionType.Leave, closeOnSuccess = true) {
            chatOperationsRepository.leaveChat(chatId)
            updateChat(chatId)
        }
    }

    override fun onJoinChat() {
        runAction(ChatActionType.Join) {
            messageRepository.joinChat(chatId)
            updateChat(chatId)
        }
    }

    override fun onReport(reason: String) {
        runAction(ChatActionType.Report) {
            chatOperationsRepository.reportChat(chatId, reason)
            _state.update { it.copy(isReportVisible = false) }
        }
    }

    override fun onDismissReport() {
        _state.update { it.copy(isReportVisible = false) }
    }

    override fun onShowReport() {
        _state.update { it.copy(isReportVisible = true) }
    }

    override fun onShowLogs() {
        onShowLogsClicked(chatId)
    }

    override fun onMemberClick(userId: Long) {
        scope.launch {
            val chat = chatListRepository.getChatById(userId)
            if (chat != null && (chat.isGroup || chat.isChannel)) {
                onMemberClicked(userId)
            } else {
                onSendMessageClicked(userId)
            }
        }
    }

    override fun onMemberLongClick(userId: Long) {
        scope.launch {
            val member = chatInfoRepository.getChatMember(chatId, userId)
            if (member?.status is ChatMemberStatus.Administrator || member?.status is ChatMemberStatus.Creator) {
                onMemberLongClicked(chatId, userId)
            }
        }
    }

    override fun onUpdateChatTitle(title: String) {
        scope.launch {
            chatSettingsRepository.setChatTitle(chatId, title)
            updateChat(chatId)
        }
    }

    override fun onUpdateChatDescription(description: String) {
        scope.launch {
            chatSettingsRepository.setChatDescription(chatId, description)
            loadData()
        }
    }

    override fun onUpdateChatUsername(username: String) {
        scope.launch {
            chatSettingsRepository.setChatUsername(chatId, username)
            loadData()
        }
    }

    override fun onUpdateChatPermissions(permissions: ChatPermissionsModel) {
        scope.launch {
            chatSettingsRepository.setChatPermissions(chatId, permissions)
            updateChat(chatId)
        }
    }

    override fun onUpdateChatSlowModeDelay(delay: Int) {
        scope.launch {
            chatSettingsRepository.setChatSlowModeDelay(chatId, delay)
            loadData()
        }
    }

    override fun onUpdateMemberStatus(userId: Long, status: ChatMemberStatus) {
        scope.launch {
            chatInfoRepository.setChatMemberStatus(chatId, userId, status)
            _state.update { it.copy(memberTabs = defaultMemberTabStates()) }
            _state.value.visibleTabs
                .asSequence()
                .map(ProfileTabSpec::key)
                .filter(ProfileTabKey::isMemberTab)
                .forEach(::ensureTabLoaded)
        }
    }

    override fun onShowStatistics() {
        scope.launch {
            val stats = chatStatisticsRepository.getChatStatistics(chatId, false)
            if (stats != null) {
                val enrichedStats = enrichInteractionPreviews(stats)
                _state.update { it.copy(statistics = enrichedStats, isStatisticsVisible = true) }
            } else {
                loadData()
            }
        }
    }

    override fun onShowRevenueStatistics() {
        scope.launch {
            val stats = chatStatisticsRepository.getChatRevenueStatistics(chatId, false)
            if (stats != null) {
                _state.update {
                    it.copy(revenueStatistics = stats, isRevenueStatisticsVisible = true)
                }
            } else {
                loadData()
            }
        }
    }

    override fun onDismissStatistics() {
        _state.update {
            it.copy(
                isStatisticsVisible = false,
                isRevenueStatisticsVisible = false,
                statistics = null,
                revenueStatistics = null
            )
        }
    }

    override fun onLoadStatisticsGraph(token: String) {
        scope.launch {
            val graph = chatStatisticsRepository.loadStatisticsGraph(chatId, token, 0L)

            if (graph != null) {
                _state.update { state ->
                    val updatedStats = state.statistics?.let { updateStatisticsWithGraph(it, token, graph) }
                    val updatedRevenueStats = state.revenueStatistics?.let {
                        updateRevenueStatisticsWithGraph(it, token, graph)
                    }
                    state.copy(
                        statistics = updatedStats ?: state.statistics,
                        revenueStatistics = updatedRevenueStats ?: state.revenueStatistics
                    )
                }
            }
        }
    }

    override fun onLinkedChatClick() {
        _state.value.linkedChat?.id?.let { onSendMessageClicked(it) }
    }

    override fun onShowPermissions() {
        val botId = _state.value.user?.id ?: return
        val permissions = mapOf(
            stringProvider.getString("location_label") to botPreferences.getWebappPermission(botId, "location"),
            stringProvider.getString("mini_app_permission_biometry") to botPreferences.getWebappPermission(botId, "biometry"),
            stringProvider.getString("terms_of_service_title") to botPreferences.getWebappPermission(botId, "tos_accepted")
        )
        _state.update { it.copy(isPermissionsVisible = true, botPermissions = permissions) }
    }

    override fun onDismissPermissions() {
        _state.update { it.copy(isPermissionsVisible = false) }
    }

    override fun onTogglePermission(permission: String) {
        val botId = _state.value.user?.id ?: return
        val key = when (permission) {
            stringProvider.getString("location_label") -> "location"
            stringProvider.getString("mini_app_permission_biometry") -> "biometry"
            stringProvider.getString("terms_of_service_title") -> "tos_accepted"
            else -> return
        }
        val current = botPreferences.getWebappPermission(botId, key)
        botPreferences.setWebappPermission(botId, key, !current)

        if (key == "tos_accepted") {
            _state.update { it.copy(isTOSAccepted = !current) }
        }

        onShowPermissions()
    }

    override fun onAcceptTOS() {
        val botId = _state.value.user?.id ?: return
        scope.launch {
            _state.update { it.copy(isAcceptingTOS = true) }
            delay(1000) // Animation delay
            botPreferences.setWebappPermission(botId, "tos_accepted", true)
            _state.update {
                it.copy(
                    isTOSVisible = false,
                    isTOSAccepted = true,
                    isAcceptingTOS = false
                )
            }
        }
    }

    override fun onDismissTOS() {
        _state.update {
            it.copy(
                isTOSVisible = false,
                pendingMiniAppUrl = null,
                pendingMiniAppName = null
            )
        }
    }

    override fun onLocationClick(lat: Double, lon: Double, address: String) {
        scope.launch {
            var finalAddress = address
            if (address == stringProvider.getString("location_label")) {
                val reverse = locationRepository.reverseGeocode(lat, lon)
                if (reverse != null) {
                    finalAddress = reverse.address?.city
                        ?: reverse.address?.toString()
                        ?: stringProvider.getString("location_label")
                }
            }
            _state.update {
                it.copy(
                    selectedLocation = ProfileComponent.LocationData(lat, lon, finalAddress)
                )
            }
        }
    }

    override fun onDismissLocation() {
        _state.update { it.copy(selectedLocation = null) }
    }

    override fun onOpenStories() {
        val firstStoryId = _state.value.activeStoryList?.stories?.firstOrNull()?.storyId
        onOpenStoriesClicked(chatId, firstStoryId)
    }

    override fun onOpenActiveStory(storyId: Int) {
        onOpenStoriesClicked(chatId, storyId)
    }

    override fun onOpenPostedStories() {
        onOpenPostedStoriesClicked(chatId, _state.value.postedStories.firstOrNull()?.id)
    }

    override fun onOpenPostedStory(storyId: Int) {
        onOpenPostedStoriesClicked(chatId, storyId)
    }

    override fun onOpenStoryArchive() {
        onOpenStoryArchiveClicked(chatId)
    }

    override fun onCreateStory() {
        onCreateStoryClicked(chatId)
    }

    private suspend fun resolveLatestPublicLink(): String? {
        val snapshot = _state.value
        val username = snapshot.chat?.username?.takeIf { it.isNotBlank() }
            ?: snapshot.user?.username?.takeIf { it.isNotBlank() }
        return when {
            username != null -> telegramLinkRepository.buildUrl(username)
            !snapshot.fullInfo?.inviteLink.isNullOrBlank() -> snapshot.fullInfo?.inviteLink
            else -> null
        }
    }

    private suspend fun enrichInteractionPreviews(stats: ChatStatisticsModel): ChatStatisticsModel {
        if (stats.recentInteractions.isEmpty()) return stats
        val enriched = stats.recentInteractions.map { interaction ->
            if (interaction.type != ChatInteractionType.MESSAGE || interaction.objectId == 0L) {
                interaction
            } else {
                val preview = coRunCatching {
                    messageRepository.getHistoryPage(
                        HistoryRequest(
                            key = ConversationKey(chatId, ConversationScope.Main),
                            anchor = HistoryAnchor.Message(interaction.objectId),
                            direction = HistoryDirection.Around,
                            limit = 1,
                            source = HistorySource.TdlibNetwork
                        )
                    )
                        .messages
                        .firstOrNull { it.id == interaction.objectId }
                        ?.content
                        ?.toStatisticsPreview()
                }.getOrNull()
                interaction.copy(previewText = preview)
            }
        }
        return stats.copy(recentInteractions = enriched)
    }

    private fun MessageContent.toStatisticsPreview(): String {
        return when (this) {
            is MessageContent.Text -> text.ifBlank { stringProvider.getString("reply_content_message") }
            is MessageContent.Photo -> caption.ifBlank { stringProvider.getString("reply_content_photo") }
            is MessageContent.Video -> caption.ifBlank { stringProvider.getString("reply_content_video") }
            is MessageContent.Gif -> caption.ifBlank { stringProvider.getString("reply_content_gif") }
            is MessageContent.Document -> caption.ifBlank { fileName.ifBlank { stringProvider.getString("logs_media_document") } }
            is MessageContent.Audio -> caption.ifBlank { title.ifBlank { stringProvider.getString("logs_media_audio") } }
            is MessageContent.Voice -> stringProvider.getString("reply_content_voice_message")
            is MessageContent.VideoNote -> stringProvider.getString("reply_content_video_message")
            is MessageContent.Sticker -> listOf(
                stringProvider.getString("reply_content_sticker"),
                emoji.ifBlank { "" }
            ).filter { it.isNotBlank() }.joinToString(" ")
            is MessageContent.Contact -> stringProvider.getString(
                "profile_statistics_preview_contact_format",
                "$firstName $lastName".trim()
            )
            is MessageContent.Location -> stringProvider.getString("location_label")
            is MessageContent.Venue -> stringProvider.getString("profile_statistics_preview_venue_format", title)
            is MessageContent.Poll -> stringProvider.getString("profile_statistics_preview_poll_format", question)
            is MessageContent.Service -> text.ifBlank { stringProvider.getString("profile_statistics_preview_service_message") }
            is MessageContent.Checklist -> title.ifBlank { stringProvider.getString("chat_mapper_checklist") }
            is MessageContent.PaidMedia -> caption.ifBlank { stringProvider.getString("chat_mapper_paid_media") }
            is MessageContent.RichMessage -> stringProvider.getString("reply_content_message")
            MessageContent.Unsupported -> stringProvider.getString("logs_media_unsupported")
        }
    }

    private fun updateStatisticsWithGraph(
        stats: ChatStatisticsModel,
        token: String,
        newGraph: StatisticsGraphModel
    ): ChatStatisticsModel {
        fun StatisticsGraphModel?.matchesToken(token: String): Boolean {
            return when (this) {
                is StatisticsGraphModel.Async -> this.token == token
                is StatisticsGraphModel.Data -> this.zoomToken == token
                else -> false
            }
        }

        return stats.copy(
            memberCountGraph = if (stats.memberCountGraph.matchesToken(token)) newGraph else stats.memberCountGraph,
            joinGraph = if (stats.joinGraph.matchesToken(token)) newGraph else stats.joinGraph,
            muteGraph = if (stats.muteGraph.matchesToken(token)) newGraph else stats.muteGraph,
            viewCountByHourGraph = if (stats.viewCountByHourGraph.matchesToken(token)) newGraph else stats.viewCountByHourGraph,
            viewCountBySourceGraph = if (stats.viewCountBySourceGraph.matchesToken(token)) newGraph else stats.viewCountBySourceGraph,
            joinBySourceGraph = if (stats.joinBySourceGraph.matchesToken(token)) newGraph else stats.joinBySourceGraph,
            languageGraph = if (stats.languageGraph.matchesToken(token)) newGraph else stats.languageGraph,
            messageContentGraph = if (stats.messageContentGraph.matchesToken(token)) newGraph else stats.messageContentGraph,
            actionGraph = if (stats.actionGraph.matchesToken(token)) newGraph else stats.actionGraph,
            dayGraph = if (stats.dayGraph.matchesToken(token)) newGraph else stats.dayGraph,
            weekGraph = if (stats.weekGraph.matchesToken(token)) newGraph else stats.weekGraph,
            topHoursGraph = if (stats.topHoursGraph.matchesToken(token)) newGraph else stats.topHoursGraph,
            messageReactionGraph = if (stats.messageReactionGraph.matchesToken(token)) newGraph else stats.messageReactionGraph,
            storyInteractionGraph = if (stats.storyInteractionGraph.matchesToken(token)) newGraph else stats.storyInteractionGraph,
            storyReactionGraph = if (stats.storyReactionGraph.matchesToken(token)) newGraph else stats.storyReactionGraph
        )
    }

    private fun updateRevenueStatisticsWithGraph(
        stats: ChatRevenueStatisticsModel,
        token: String,
        newGraph: StatisticsGraphModel
    ): ChatRevenueStatisticsModel {
        fun StatisticsGraphModel?.matchesToken(token: String): Boolean {
            return when (this) {
                is StatisticsGraphModel.Async -> this.token == token
                is StatisticsGraphModel.Data -> this.zoomToken == token
                else -> false
            }
        }

        return stats.copy(
            revenueByHourGraph = if (stats.revenueByHourGraph.matchesToken(token)) newGraph else stats.revenueByHourGraph,
            revenueGraph = if (stats.revenueGraph.matchesToken(token)) newGraph else stats.revenueGraph
        )
    }

    private fun updateChat(chatId: Long) {
        scope.launch {
            val updatedChat = chatListRepository.getChatById(chatId)
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        chat = updatedChat,
                        isBlocked = updatedChat?.blockList ?: it.isBlocked
                    )
                }
            }
        }
    }

    private fun performProfileSettingChange(
        optimisticUpdate: (ProfileComponent.State) -> ProfileComponent.State,
        rollbackUpdate: (ProfileComponent.State) -> ProfileComponent.State,
        block: suspend () -> Unit
    ) {
        _state.update(optimisticUpdate)
        scope.launch {
            runCatching { block() }
                .onFailure { error ->
                    _state.update(rollbackUpdate)
                    messageDisplayer.show(error.message ?: "Action failed")
                }
            loadData()
        }
    }

    private fun runAction(
        action: ChatActionType,
        closeOnSuccess: Boolean = false,
        block: suspend () -> Unit
    ) {
        if (_state.value.actionState is ChatActionState.Pending) return
        scope.launch {
            _state.update { it.copy(actionState = ChatActionState.Pending(action)) }
            runCatching { block() }
                .onSuccess {
                    _state.update { it.copy(actionState = ChatActionState.Success(action)) }
                    if (closeOnSuccess) {
                        onBackClicked()
                    }
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
            _state.update { it.copy(actionState = ChatActionState.Idle) }
        }
    }

    companion object {
        private const val PROFILE_ACTIVE_STORIES_LIMIT = 20
        private const val PROFILE_POSTED_STORIES_LIMIT = 50
    }
}
