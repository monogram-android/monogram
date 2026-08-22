package org.monogram.presentation.features.stories

import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.monogram.domain.models.UserModel
import org.monogram.domain.models.stories.ActiveStoryListModel
import org.monogram.domain.models.stories.StoryComposerDraftModel
import org.monogram.domain.models.stories.StoryComposerMediaItemModel
import org.monogram.domain.models.stories.StoryInteractionActorType
import org.monogram.domain.models.stories.StoryInteractionModel
import org.monogram.domain.models.stories.StoryInteractionPageModel
import org.monogram.domain.models.stories.StoryListType
import org.monogram.domain.models.stories.StoryMediaType
import org.monogram.domain.models.stories.StoryModel
import org.monogram.domain.models.stories.StoryOptionsModel
import org.monogram.domain.models.stories.StoryPostResultModel
import org.monogram.domain.models.stories.StoryPrivacyMode
import org.monogram.domain.models.stories.StoryPrivacySettingsModel
import org.monogram.domain.models.stories.StoryReactionModel
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.AuthRepository
import org.monogram.domain.repository.AuthStep
import org.monogram.domain.repository.ChatListRepository
import org.monogram.domain.repository.StoryRepository
import org.monogram.domain.repository.StringProvider
import org.monogram.domain.repository.TelegramLinkRepository
import org.monogram.domain.repository.UserRepository
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext

class DefaultStoriesHostComponent(
    context: AppComponentContext,
    private val onProfileClicked: (Long) -> Unit = {}
) : StoriesHostComponent, AppComponentContext by context {
    private val authRepository: AuthRepository = container.repositories.authRepository
    private val storyRepository: StoryRepository = container.repositories.storyRepository
    private val chatListRepository: ChatListRepository = container.repositories.chatListRepository
    private val userRepository: UserRepository = container.repositories.userRepository
    private val appPreferences: AppPreferencesProvider =
        container.preferences.appPreferencesProvider
    private val telegramLinkRepository: TelegramLinkRepository =
        container.repositories.telegramLinkRepository
    private val messageDisplayer = container.utils.messageDisplayer()
    private val clipManager = container.utils.clipManager
    private val externalNavigator = container.utils.externalNavigator()
    private val stringProvider = container.utils.stringProvider()
    private val scope = componentScope
    private var hasLoadedActiveStories = false
    private var storyLoadJob: Job? = null
    private var storyRefreshJob: Job? = null
    private var audienceLoadJob: Job? = null
    private var audienceSearchJob: Job? = null
    private var storyMediaLoadingMessageJob: Job? = null
    private var storyMediaLoadingMessageKey: Pair<Long, Int>? = null
    private val chatPresentationCache = mutableMapOf<Long, ChatPresentation>()

    private val _state = MutableStateFlow(createDefaultState())
    override val state = _state.asStateFlow()

    init {
        scope.launch {
            userRepository.currentUserFlow.collect { user ->
                _state.value = _state.value.copy(
                    currentUserId = user?.id,
                    isPremiumUser = user?.isPremium == true
                )
            }
        }
        scope.launch {
            appPreferences.storyMediaStretchEnabled.collect { enabled ->
                _state.value = _state.value.copy(isStoryMediaStretchEnabled = enabled)
            }
        }
        scope.launch {
            storyRepository.stealthMode.collect { stealthMode ->
                _state.value = _state.value.copy(stealthMode = stealthMode)
            }
        }
        scope.launch {
            storyRepository.storyOptions.collect { storyOptions ->
                _state.value = _state.value.copy(storyOptions = storyOptions)
            }
        }
        scope.launch {
            Log.d(TAG, "initializing stories host")
            authRepository.authState
                .collect { authState ->
                    when (authState) {
                        is AuthStep.Ready -> {
                            if (!hasLoadedActiveStories) {
                                hasLoadedActiveStories = true
                                Log.d(TAG, "auth ready, loading active stories")
                                storyRepository.refreshStoryOptions()
                                storyRepository.loadActiveStories(StoryListType.MAIN)
                                storyRepository.loadActiveStories(StoryListType.ARCHIVE)
                            }
                        }

                        else -> {
                            hasLoadedActiveStories = false
                        }
                    }
                }
        }
    }

    override fun openChatStories(
        chatId: Long,
        storyId: Int?,
        listType: StoryListType
    ) {
        storyLoadJob?.cancel()
        storyRefreshJob?.cancel()
        _state.value = _state.value.copy(
            mode = StoriesHostComponent.Mode.Viewer,
            isLoading = true,
            chatId = chatId,
            chatTitle = "",
            chatAvatarPath = null,
            viewerItems = emptyList(),
            viewerIndex = 0,
            currentStory = null,
            viewerSource = StoryViewerSource.ACTIVE,
            activeListType = listType,
            canManageStories = false,
            composerMode = StoryComposerMode.CREATE,
            editingStoryId = null,
            audiencePicker = StoryAudiencePickerState(),
            inlineError = null,
            isSubmitting = false,
            isStoryStatisticsVisible = false,
            isStoryStatisticsLoading = false,
            storyStatistics = null,
            isStoryInteractionsVisible = false,
            isStoryInteractionsLoading = false,
            storyInteractionsPage = null,
            isStoryReactionPickerVisible = false,
            isStoryReactionPickerLoading = false,
            showInlineVideo = false,
            showStoryMediaLoadingMessage = false
        )
        syncStoryMediaLoadingMessage()
        Log.d(TAG, "viewer placeholder shown chatId=$chatId listType=$listType")
        storyLoadJob = scope.launch {
            Log.d(TAG, "openChatStories chatId=$chatId storyId=$storyId listType=$listType")

            val activeStories = storyRepository.activeStories.value[listType]
                .orEmpty()
                .let { loadedStories ->
                    if (loadedStories.any { it.chatId == chatId }) {
                        loadedStories
                    } else {
                        loadedStories + listOfNotNull(
                            storyRepository.getChatActiveStories(chatId)
                                ?.takeIf { it.listType == listType }
                        )
                    }
                }
            val items = buildViewerItems(activeStories)

            if (items.isEmpty()) {
                val title = resolveChatTitle(chatId)
                _state.value = _state.value.copy(
                    mode = StoriesHostComponent.Mode.Hidden,
                    isLoading = false,
                    inlineError = null
                )
                messageDisplayer.show("No stories available for $title")
                return@launch
            }

            val resolvedIndex = resolveInitialViewerIndex(items, chatId, storyId)
            val item = items[resolvedIndex]
            val story = loadStory(item)
            val chatPresentation = resolveChatPresentation(item.chatId)

            _state.value = _state.value.copy(
                mode = StoriesHostComponent.Mode.Viewer,
                isLoading = story.requiresMediaRefresh(),
                chatId = item.chatId,
                chatTitle = chatPresentation.title,
                chatAvatarPath = chatPresentation.avatarPath,
                viewerItems = items,
                viewerIndex = resolvedIndex,
                currentStory = story,
                viewerSource = StoryViewerSource.ACTIVE,
                canManageStories = chatPresentation.canManageStories,
                inlineError = null,
                showInlineVideo = false,
                showStoryMediaLoadingMessage = false
            )
            syncStoryMediaLoadingMessage()
            scheduleStoryRefreshIfNeeded(item, story)
        }
    }

    override fun openStoryAlbum(chatId: Long, albumId: Int) {
        storyRefreshJob?.cancel()
        _state.value = _state.value.copy(
            mode = StoriesHostComponent.Mode.Viewer,
            isLoading = true,
            chatId = chatId,
            chatTitle = "",
            chatAvatarPath = null,
            viewerItems = emptyList(),
            viewerIndex = 0,
            currentStory = null,
            viewerSource = StoryViewerSource.ALBUM,
            activeListType = StoryListType.MAIN,
            canManageStories = false,
            composerMode = StoryComposerMode.CREATE,
            editingStoryId = null,
            audiencePicker = StoryAudiencePickerState(),
            inlineError = null,
            isSubmitting = false,
            isStoryStatisticsVisible = false,
            isStoryStatisticsLoading = false,
            storyStatistics = null,
            isStoryInteractionsVisible = false,
            isStoryInteractionsLoading = false,
            storyInteractionsPage = null,
            isStoryReactionPickerVisible = false,
            isStoryReactionPickerLoading = false,
            showInlineVideo = false,
            showStoryMediaLoadingMessage = false
        )
        syncStoryMediaLoadingMessage()
        Log.d(TAG, "viewer placeholder shown for album chatId=$chatId albumId=$albumId")
        scope.launch {
            Log.d(TAG, "openStoryAlbum chatId=$chatId albumId=$albumId")

            val stories = storyRepository.getStoryAlbum(chatId, albumId)
            val items =
                stories.map { StoryViewerUiModel(chatId = chatId, storyId = it.id, date = it.date) }
            if (items.isEmpty()) {
                _state.value =
                    _state.value.copy(mode = StoriesHostComponent.Mode.Hidden, isLoading = false)
                messageDisplayer.show("Story album is empty")
                return@launch
            }

            _state.value = _state.value.copy(
                mode = StoriesHostComponent.Mode.Viewer,
                isLoading = stories.first().requiresMediaRefresh(),
                chatId = chatId,
                chatTitle = resolveChatTitle(chatId),
                chatAvatarPath = resolveChatAvatar(chatId),
                viewerItems = items,
                viewerIndex = 0,
                currentStory = stories.first(),
                viewerSource = StoryViewerSource.ALBUM,
                canManageStories = canManageStories(chatId),
                inlineError = null,
                showInlineVideo = false,
                showStoryMediaLoadingMessage = false
            )
            syncStoryMediaLoadingMessage()
            scheduleStoryRefreshIfNeeded(items.first(), stories.first())
        }
    }

    override fun openProfileStories(chatId: Long, storyId: Int?) {
        openProfileStoryPage(
            chatId = chatId,
            storyId = storyId,
            viewerSource = StoryViewerSource.PROFILE,
            loadPage = {
                storyRepository.getChatPostedToChatPageStories(
                    chatId = chatId,
                    limit = PROFILE_STORIES_PAGE_SIZE
                )
            },
            emptyMessage = "No profile stories available"
        )
    }

    override fun openProfileStoryArchive(chatId: Long, storyId: Int?) {
        openProfileStoryPage(
            chatId = chatId,
            storyId = storyId,
            viewerSource = StoryViewerSource.PROFILE_ARCHIVE,
            loadPage = {
                storyRepository.getChatArchivedStories(
                    chatId = chatId,
                    limit = PROFILE_STORIES_PAGE_SIZE
                )
            },
            emptyMessage = "No archived profile stories available"
        )
    }

    private fun openProfileStoryPage(
        chatId: Long,
        storyId: Int?,
        viewerSource: StoryViewerSource,
        loadPage: suspend () -> org.monogram.domain.models.stories.StoryPageModel?,
        emptyMessage: String
    ) {
        storyLoadJob?.cancel()
        storyRefreshJob?.cancel()
        _state.value = _state.value.copy(
            mode = StoriesHostComponent.Mode.Viewer,
            isLoading = true,
            chatId = chatId,
            chatTitle = "",
            chatAvatarPath = null,
            viewerItems = emptyList(),
            viewerIndex = 0,
            currentStory = null,
            viewerSource = StoryViewerSource.PROFILE,
            activeListType = StoryListType.MAIN,
            canManageStories = false,
            composerMode = StoryComposerMode.CREATE,
            editingStoryId = null,
            audiencePicker = StoryAudiencePickerState(),
            inlineError = null,
            isSubmitting = false,
            isStoryStatisticsVisible = false,
            isStoryStatisticsLoading = false,
            storyStatistics = null,
            isStoryInteractionsVisible = false,
            isStoryInteractionsLoading = false,
            storyInteractionsPage = null,
            isStoryReactionPickerVisible = false,
            isStoryReactionPickerLoading = false,
            showInlineVideo = false,
            showStoryMediaLoadingMessage = false
        )
        syncStoryMediaLoadingMessage()
        scope.launch {
            val page = loadPage()
            val stories = page?.stories.orEmpty()
            val items = stories.map { story ->
                StoryViewerUiModel(
                    chatId = story.posterChatId,
                    storyId = story.id,
                    date = story.date
                )
            }

            if (items.isEmpty()) {
                _state.value = _state.value.copy(
                    mode = StoriesHostComponent.Mode.Hidden,
                    isLoading = false,
                    inlineError = null
                )
                messageDisplayer.show(emptyMessage)
                return@launch
            }

            val resolvedIndex = resolveInitialViewerIndex(items, chatId, storyId)
            val item = items[resolvedIndex]
            val story = loadStory(item) ?: stories.getOrNull(resolvedIndex)
            val chatPresentation = resolveChatPresentation(item.chatId)

            _state.value = _state.value.copy(
                mode = StoriesHostComponent.Mode.Viewer,
                isLoading = story.requiresMediaRefresh(),
                chatId = item.chatId,
                chatTitle = chatPresentation.title,
                chatAvatarPath = chatPresentation.avatarPath,
                viewerItems = items,
                viewerIndex = resolvedIndex,
                currentStory = story,
                viewerSource = viewerSource,
                canManageStories = chatPresentation.canManageStories,
                inlineError = null,
                showInlineVideo = false,
                showStoryMediaLoadingMessage = false
            )
            syncStoryMediaLoadingMessage()
            scheduleStoryRefreshIfNeeded(item, story)
        }
    }

    override fun openComposer(
        chatId: Long,
        preferredMediaType: StoryMediaType?,
        initialSourcePath: String?,
        initialCaption: String,
        widgetLink: String?
    ) {
        showComposer(
            chatId = chatId,
            composerMode = StoryComposerMode.CREATE,
            editingStoryId = null,
            draft = createComposerDraft(
                preferredMediaType = preferredMediaType,
                initialSourcePath = initialSourcePath,
                initialCaption = initialCaption,
                widgetLink = widgetLink
            )
        )
    }

    override fun editCurrentStory() {
        val current = _state.value
        val story = current.currentStory ?: return
        val mediaPath = resolveStoryEditableMediaPath(story)
        if (!story.canBeEdited || story.privacy == null || mediaPath == null) {
            _state.value = current.copy(inlineError = "This story can't be edited yet")
            return
        }

        showComposer(
            chatId = story.posterChatId,
            composerMode = StoryComposerMode.EDIT,
            editingStoryId = story.id,
            draft = createEditComposerDraft(story, mediaPath)
        )
    }

    private fun showComposer(
        chatId: Long,
        composerMode: StoryComposerMode,
        editingStoryId: Int?,
        draft: StoryComposerDraftModel
    ) {
        storyRefreshJob?.cancel()
        syncStoryMediaLoadingMessage(disableOnly = true)
        _state.value = _state.value.copy(
            mode = StoriesHostComponent.Mode.Composer,
            isLoading = composerMode == StoryComposerMode.CREATE,
            chatId = chatId,
            chatTitle = "",
            chatAvatarPath = null,
            canManageStories = false,
            composerMode = composerMode,
            editingStoryId = editingStoryId,
            composerDraft = draft,
            audiencePicker = StoryAudiencePickerState(),
            postCapability = null,
            inlineError = null,
            isSubmitting = false,
            isStoryStatisticsVisible = false,
            isStoryStatisticsLoading = false,
            storyStatistics = null,
            isStoryInteractionsVisible = false,
            isStoryInteractionsLoading = false,
            storyInteractionsPage = null,
            isStoryReactionPickerVisible = false,
            isStoryReactionPickerLoading = false,
            showMediaPicker = !draft.isValid,
            showCamera = false,
            showInlineVideo = false,
            showStoryMediaLoadingMessage = false
        )
        Log.d(TAG, "composer placeholder shown chatId=$chatId")
        scope.launch {
            val capability =
                if (composerMode == StoryComposerMode.CREATE) storyRepository.canPostStory(chatId)
                else null
            Log.d(TAG, "openComposer chatId=$chatId mode=$composerMode capability=$capability")
            val latest = _state.value
            if (
                latest.mode != StoriesHostComponent.Mode.Composer ||
                latest.chatId != chatId ||
                latest.composerMode != composerMode ||
                latest.editingStoryId != editingStoryId
            ) {
                return@launch
            }

            _state.value = latest.copy(
                isLoading = false,
                chatTitle = resolveChatTitle(chatId),
                chatAvatarPath = resolveChatAvatar(chatId),
                canManageStories = canManageStories(chatId),
                postCapability = capability,
                showStoryMediaLoadingMessage = false
            )
        }
    }

    override fun dismiss() {
        storyLoadJob?.cancel()
        storyRefreshJob?.cancel()
        audienceLoadJob?.cancel()
        audienceSearchJob?.cancel()
        syncStoryMediaLoadingMessage(disableOnly = true)
        val current = _state.value
        if (
            current.mode == StoriesHostComponent.Mode.Composer &&
            current.composerMode == StoryComposerMode.EDIT &&
            current.currentStory != null &&
            current.viewerItems.isNotEmpty()
        ) {
            _state.value = restoreViewerState(current)
            return
        }
        scope.launch {
            state.value.currentStory?.let {
                storyRepository.closeStory(it.posterChatId, it.id)
            }
        }
        _state.value = createDefaultState()
    }

    override fun nextStory() {
        val current = _state.value
        if (!current.canGoNext) return
        openStoryAt(current.viewerIndex + 1)
    }

    override fun previousStory() {
        val current = _state.value
        if (!current.canGoPrevious) return
        openStoryAt(current.viewerIndex - 1)
    }

    override fun openMediaPicker() {
        _state.value = _state.value.copy(showMediaPicker = true, showCamera = false)
    }

    override fun dismissMediaPicker() {
        _state.value = _state.value.copy(showMediaPicker = false)
    }

    override fun showCamera() {
        _state.value = _state.value.copy(showMediaPicker = false, showCamera = true)
    }

    override fun dismissCamera() {
        _state.value = _state.value.copy(showCamera = false)
    }

    override fun attachMedia(path: String, mediaType: StoryMediaType) {
        val currentDraft = _state.value.composerDraft
        val mediaItem = StoryComposerMediaItemModel(
            sourcePath = path,
            mediaType = mediaType
        )
        _state.value = _state.value.copy(
            composerDraft = currentDraft.replaceCurrentMedia(mediaItem),
            showMediaPicker = false,
            showCamera = false,
            inlineError = null
        )
    }

    override fun attachMedia(items: List<StoryComposerMediaItemModel>) {
        if (items.isEmpty()) return
        val resolvedItems = if (_state.value.composerMode == StoryComposerMode.EDIT) {
            items.take(1)
        } else {
            items
        }
        _state.value = _state.value.copy(
            composerDraft = _state.value.composerDraft.replaceAllMedia(resolvedItems),
            showMediaPicker = false,
            showCamera = false,
            inlineError = null
        )
    }

    override fun selectComposerMedia(index: Int) {
        _state.value = _state.value.copy(
            composerDraft = _state.value.composerDraft.selectMedia(index)
        )
    }

    override fun updateCaption(caption: String) {
        _state.value = _state.value.copy(
            composerDraft = _state.value.composerDraft.copy(caption = caption)
        )
    }

    override fun updatePrivacy(mode: StoryPrivacyUi) {
        val updatedDraft = _state.value.composerDraft.copy(
            privacy = updateStoryPrivacyMode(_state.value.composerDraft.privacy, mode)
        )
        _state.value = _state.value.copy(composerDraft = updatedDraft)
        if (
            mode == StoryPrivacyUi.SELECTED_USERS &&
            updatedDraft.privacy.selectedUserIds.isEmpty()
        ) {
            showAudiencePicker(StoryAudienceFilterMode.SHOW_TO)
        }
    }

    override fun showAudiencePicker(filterMode: StoryAudienceFilterMode) {
        audienceLoadJob?.cancel()
        audienceSearchJob?.cancel()

        val current = _state.value
        val selectedIds = resolveAudienceSelectionIds(current.composerDraft.privacy, filterMode)
        _state.value = current.copy(
            audiencePicker = current.audiencePicker.copy(
                isVisible = true,
                filterMode = filterMode,
                searchQuery = "",
                searchResults = emptyList(),
                isLoading = true,
                isSearching = false
            )
        )

        audienceLoadJob = scope.launch {
            val contacts = userRepository.getContacts()
            val selectedUsers = resolveAudienceUsers(selectedIds, contacts)
            val mergedContacts = mergeAudienceUsers(contacts, selectedUsers)
            val latest = _state.value
            if (
                latest.mode != StoriesHostComponent.Mode.Composer ||
                !latest.audiencePicker.isVisible ||
                latest.audiencePicker.filterMode != filterMode
            ) {
                return@launch
            }
            _state.value = latest.copy(
                audiencePicker = latest.audiencePicker.copy(
                    contacts = mergedContacts,
                    selectedUsers = selectedUsers,
                    isLoading = false
                )
            )
        }
    }

    override fun dismissAudiencePicker() {
        audienceSearchJob?.cancel()
        val current = _state.value
        _state.value = current.copy(
            audiencePicker = current.audiencePicker.copy(
                isVisible = false,
                searchQuery = "",
                searchResults = emptyList(),
                isSearching = false
            )
        )
    }

    override fun updateAudienceSearchQuery(query: String) {
        audienceSearchJob?.cancel()
        val current = _state.value
        _state.value = current.copy(
            audiencePicker = current.audiencePicker.copy(
                searchQuery = query,
                searchResults = if (query.isBlank()) emptyList() else current.audiencePicker.searchResults,
                isSearching = query.isNotBlank()
            )
        )

        if (query.isBlank()) {
            return
        }

        audienceSearchJob = scope.launch {
            val results = userRepository.searchContacts(query)
            val latest = _state.value
            if (latest.audiencePicker.searchQuery != query) {
                return@launch
            }
            _state.value = latest.copy(
                audiencePicker = latest.audiencePicker.copy(
                    searchResults = mergeAudienceUsers(
                        results,
                        latest.audiencePicker.selectedUsers
                    ),
                    isSearching = false
                )
            )
        }
    }

    override fun toggleAudienceUserSelection(userId: Long) {
        val current = _state.value
        val updatedPrivacy = toggleStoryAudienceUser(
            current.composerDraft.privacy,
            userId,
            current.audiencePicker.filterMode
        )
        val selectedIds =
            resolveAudienceSelectionIds(updatedPrivacy, current.audiencePicker.filterMode)
        val selectedUsers = selectedIds.mapNotNull { selectedId ->
            current.audiencePicker.selectedUsers.find { it.id == selectedId }
                ?: current.audiencePicker.contacts.find { it.id == selectedId }
                ?: current.audiencePicker.searchResults.find { it.id == selectedId }
        }
        _state.value = current.copy(
            composerDraft = current.composerDraft.copy(privacy = updatedPrivacy),
            audiencePicker = current.audiencePicker.copy(selectedUsers = selectedUsers)
        )
    }

    override fun clearAudienceSelection() {
        val current = _state.value
        val clearedPrivacy = clearStoryAudienceSelection(
            current.composerDraft.privacy,
            current.audiencePicker.filterMode
        )
        _state.value = current.copy(
            composerDraft = current.composerDraft.copy(privacy = clearedPrivacy),
            audiencePicker = current.audiencePicker.copy(selectedUsers = emptyList())
        )
    }

    override fun updateActivePeriod(seconds: Int) {
        _state.value = _state.value.copy(
            composerDraft = _state.value.composerDraft.copy(activePeriodSeconds = seconds)
        )
    }

    override fun updateProtectContent(protectContent: Boolean) {
        _state.value = _state.value.copy(
            composerDraft = _state.value.composerDraft.copy(protectContent = protectContent)
        )
    }

    override fun updateKeepOnProfile(keepOnProfile: Boolean) {
        _state.value = _state.value.copy(
            composerDraft = _state.value.composerDraft.copy(keepOnProfile = keepOnProfile)
        )
    }

    override fun saveStory() {
        val current = _state.value
        val chatId = current.chatId ?: return
        val validationError = resolveStorySaveValidationError(
            stringProvider = stringProvider,
            draft = current.composerDraft,
            isPremiumUser = current.isPremiumUser,
            storyOptions = current.storyOptions
        )
        if (validationError != null) {
            _state.value = current.copy(inlineError = validationError)
            return
        }

        _state.value = current.copy(isSubmitting = true, inlineError = null)
        scope.launch {
            Log.d(
                TAG,
                "saveStory chatId=$chatId mode=${current.composerMode} mediaCount=${current.composerDraft.mediaCount} mediaType=${current.composerDraft.mediaType}"
            )
            when (
                val result = saveStoryDraft(
                    storyRepository = storyRepository,
                    stringProvider = stringProvider,
                    chatId = chatId,
                    composerMode = current.composerMode,
                    editingStoryId = current.editingStoryId,
                    draft = _state.value.composerDraft
                )
            ) {
                is StorySaveOutcome.Created -> {
                    Log.d(TAG, "saveStory create success chatId=$chatId storyId=${result.story.id}")
                    result.message?.let(messageDisplayer::show)
                    _state.value = createDefaultState()
                    openChatStories(chatId, result.story.id)
                }

                is StorySaveOutcome.Edited -> {
                    Log.d(TAG, "saveStory edit success chatId=$chatId storyId=${result.storyId}")
                    val listType = current.activeListType
                    val viewerSource = current.viewerSource
                    _state.value = restoreViewerState(
                        _state.value.copy(
                            isSubmitting = false,
                            composerMode = StoryComposerMode.CREATE,
                            editingStoryId = null
                        )
                    )
                    reopenStory(
                        chatId = chatId,
                        storyId = result.storyId,
                        viewerSource = viewerSource,
                        activeListType = listType
                    )
                }

                is StorySaveOutcome.Failed -> {
                    Log.d(TAG, "saveStory failed chatId=$chatId message=${result.message}")
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        inlineError = result.message
                    )
                }
            }
        }
    }

    override fun deleteCurrentStory() {
        val story = _state.value.currentStory ?: return
        scope.launch {
            val deleted = storyRepository.deleteStory(story.posterChatId, story.id)
            if (!deleted) {
                _state.value = _state.value.copy(inlineError = "Failed to delete story")
                return@launch
            }
            val remaining = _state.value.viewerItems.filterNot {
                it.chatId == story.posterChatId && it.storyId == story.id
            }
            if (remaining.isEmpty()) {
                dismiss()
            } else {
                val nextIndex = _state.value.viewerIndex.coerceAtMost(remaining.lastIndex)
                _state.value = _state.value.copy(viewerItems = remaining, viewerIndex = nextIndex)
                openStoryAt(nextIndex)
            }
        }
    }

    override fun moveCurrentStoryToArchive() {
        if (_state.value.viewerSource != StoryViewerSource.ACTIVE) return
        val chatId = _state.value.chatId ?: return
        scope.launch {
            if (storyRepository.setChatActiveStoriesList(chatId, StoryListType.ARCHIVE)) {
                _state.value = _state.value.copy(activeListType = StoryListType.ARCHIVE)
                storyRepository.loadActiveStories(StoryListType.ARCHIVE)
            } else {
                _state.value = _state.value.copy(inlineError = "Failed to archive stories")
            }
        }
    }

    override fun restoreCurrentStoryFromArchive() {
        if (_state.value.viewerSource != StoryViewerSource.ACTIVE) return
        val chatId = _state.value.chatId ?: return
        scope.launch {
            if (storyRepository.setChatActiveStoriesList(chatId, StoryListType.MAIN)) {
                _state.value = _state.value.copy(activeListType = StoryListType.MAIN)
                storyRepository.loadActiveStories(StoryListType.MAIN)
            } else {
                _state.value = _state.value.copy(inlineError = "Failed to restore stories")
            }
        }
    }

    override fun toggleCurrentStoryPostedToProfile() {
        val current = _state.value
        val story = current.currentStory ?: return
        if (!story.canToggleIsPostedToChatPage) return

        val targetValue = !story.isPostedToChatPage
        scope.launch {
            val toggled = storyRepository.toggleStoryPostedToChatPage(
                chatId = story.posterChatId,
                storyId = story.id,
                isPostedToChatPage = targetValue
            )
            if (!toggled) {
                _state.value = _state.value.copy(
                    inlineError = if (targetValue) {
                        "Failed to keep story on profile"
                    } else {
                        "Failed to remove story from profile"
                    }
                )
                return@launch
            }

            val updatedStory = storyRepository.getStory(story.posterChatId, story.id) ?: story.copy(
                isPostedToChatPage = targetValue
            )

            if (current.viewerSource == StoryViewerSource.PROFILE && !updatedStory.isPostedToChatPage) {
                val remaining = current.viewerItems.filterNot {
                    it.chatId == story.posterChatId && it.storyId == story.id
                }
                if (remaining.isEmpty()) {
                    dismiss()
                } else {
                    val nextIndex = current.viewerIndex.coerceAtMost(remaining.lastIndex)
                    _state.value =
                        _state.value.copy(viewerItems = remaining, viewerIndex = nextIndex)
                    openStoryAt(nextIndex)
                }
            } else if (
                current.viewerSource == StoryViewerSource.PROFILE_ARCHIVE &&
                updatedStory.isPostedToChatPage
            ) {
                val remaining = current.viewerItems.filterNot {
                    it.chatId == story.posterChatId && it.storyId == story.id
                }
                if (remaining.isEmpty()) {
                    dismiss()
                } else {
                    val nextIndex = current.viewerIndex.coerceAtMost(remaining.lastIndex)
                    _state.value =
                        _state.value.copy(viewerItems = remaining, viewerIndex = nextIndex)
                    openStoryAt(nextIndex)
                }
            } else {
                _state.value = _state.value.copy(
                    currentStory = updatedStory,
                    inlineError = null
                )
            }
        }
    }

    override fun showStoryStatistics() {
        val story = _state.value.currentStory ?: return
        if (!story.canGetStatistics) return

        _state.value = _state.value.copy(
            isStoryStatisticsVisible = true,
            isStoryStatisticsLoading = true,
            storyStatistics = null,
            inlineError = null
        )
        scope.launch {
            val statistics = storyRepository.getStoryStatistics(
                chatId = story.posterChatId,
                storyId = story.id,
                isDark = false
            )
            if (statistics == null) {
                _state.value = _state.value.copy(
                    isStoryStatisticsVisible = false,
                    isStoryStatisticsLoading = false,
                    storyStatistics = null,
                    inlineError = "Failed to load story statistics"
                )
            } else {
                _state.value = _state.value.copy(
                    isStoryStatisticsVisible = true,
                    isStoryStatisticsLoading = false,
                    storyStatistics = statistics,
                    inlineError = null
                )
            }
        }
    }

    override fun dismissStoryStatistics() {
        _state.value = _state.value.copy(
            isStoryStatisticsVisible = false,
            isStoryStatisticsLoading = false,
            storyStatistics = null
        )
    }

    override fun showStoryInteractions() {
        val story = _state.value.currentStory ?: return
        if (!story.canGetInteractions) return

        _state.value = _state.value.copy(
            isStoryInteractionsVisible = true,
            isStoryInteractionsLoading = true,
            storyInteractionsPage = null,
            inlineError = null
        )
        scope.launch {
            val rawPage = storyRepository.getStoryInteractions(
                chatId = story.posterChatId,
                storyId = story.id,
                offset = "",
                limit = STORY_INTERACTIONS_PAGE_SIZE
            )
            val page = rawPage?.let { enrichStoryInteractions(it) }
            if (page == null) {
                _state.value = _state.value.copy(
                    isStoryInteractionsVisible = false,
                    isStoryInteractionsLoading = false,
                    storyInteractionsPage = null,
                    inlineError = "Failed to load story interactions"
                )
            } else {
                _state.value = _state.value.copy(
                    isStoryInteractionsVisible = true,
                    isStoryInteractionsLoading = false,
                    storyInteractionsPage = page,
                    inlineError = null
                )
            }
        }
    }

    override fun dismissStoryInteractions() {
        _state.value = _state.value.copy(
            isStoryInteractionsVisible = false,
            isStoryInteractionsLoading = false,
            storyInteractionsPage = null,
            isStoryReactionPickerVisible = false,
            isStoryReactionPickerLoading = false
        )
    }

    override fun loadMoreStoryInteractions() {
        val current = _state.value
        val story = current.currentStory ?: return
        val page = current.storyInteractionsPage ?: return
        if (current.isStoryInteractionsLoading || !page.canLoadMore || !story.canGetInteractions) {
            return
        }

        _state.value = current.copy(isStoryInteractionsLoading = true, inlineError = null)
        scope.launch {
            val rawNextPage = storyRepository.getStoryInteractions(
                chatId = story.posterChatId,
                storyId = story.id,
                offset = page.nextOffset,
                limit = STORY_INTERACTIONS_PAGE_SIZE
            )
            val nextPage = rawNextPage?.let { enrichStoryInteractions(it) }
            if (nextPage == null) {
                _state.value = _state.value.copy(
                    isStoryInteractionsLoading = false,
                    inlineError = "Failed to load more story interactions"
                )
            } else {
                _state.value = _state.value.copy(
                    isStoryInteractionsLoading = false,
                    storyInteractionsPage = page.mergeWith(nextPage),
                    inlineError = null
                )
            }
        }
    }

    override fun showStoryReactionPicker() {
        val current = _state.value
        val story = current.currentStory ?: return
        if (current.currentUserId == story.posterChatId) return

        val cached = current.storyAvailableReactions
        if (cached?.hasAnyReactionOption == true) {
            _state.value = current.copy(
                isStoryReactionPickerVisible = true,
                isStoryReactionPickerLoading = false,
                inlineError = null
            )
            return
        }

        _state.value = current.copy(
            isStoryReactionPickerVisible = true,
            isStoryReactionPickerLoading = true,
            inlineError = null
        )
        scope.launch {
            val availableReactions = storyRepository.getStoryAvailableReactions()
            if (availableReactions == null) {
                _state.value = _state.value.copy(
                    isStoryReactionPickerVisible = false,
                    isStoryReactionPickerLoading = false,
                    inlineError = "Failed to load story reactions"
                )
            } else {
                _state.value = _state.value.copy(
                    isStoryReactionPickerVisible = true,
                    isStoryReactionPickerLoading = false,
                    storyAvailableReactions = availableReactions,
                    inlineError = null
                )
            }
        }
    }

    override fun dismissStoryReactionPicker() {
        _state.value = _state.value.copy(
            isStoryReactionPickerVisible = false,
            isStoryReactionPickerLoading = false
        )
    }

    override fun activateStealthMode() {
        val current = _state.value
        val story = current.currentStory ?: return
        val currentUserId = current.currentUserId
        val nowSeconds = (System.currentTimeMillis() / 1000L).toInt()
        if (!current.isPremiumUser || currentUserId == null || story.posterChatId == currentUserId) {
            return
        }
        if (
            current.stealthMode.isActiveAt(nowSeconds) ||
            current.stealthMode.isCoolingDownAt(nowSeconds)
        ) {
            return
        }

        scope.launch {
            val activated = storyRepository.activateStealthMode()
            if (!activated) {
                _state.value = _state.value.copy(
                    inlineError = stringProvider.getString("story_stealth_mode_failed")
                )
            } else {
                messageDisplayer.show(
                    stringProvider.getString(
                        "story_stealth_mode_enabled",
                        stringProvider.getCompactStoryDuration(current.storyOptions.stealthModeFuturePeriod)
                    )
                )
            }
        }
    }

    override fun openProfile(chatId: Long) {
        dismiss()
        onProfileClicked(chatId)
    }

    override fun openStoryLink(url: String) {
        externalNavigator.openUrl(url)
    }

    override fun copyStoryLink(url: String) {
        clipManager.copyToClipboard("story_link", url)
        messageDisplayer.show(stringProvider.getString("link_copied"))
    }

    override fun copyCurrentStoryLink() {
        val story = _state.value.currentStory ?: return
        scope.launch {
            val username = resolvePublicStoryUsername(story.posterChatId)
            if (username == null) {
                messageDisplayer.show(stringProvider.getString("story_public_link_unavailable"))
                return@launch
            }

            val link = telegramLinkRepository.buildUrl("$username/s/${story.id}")
            clipManager.copyToClipboard("story_link", link)
            messageDisplayer.show(stringProvider.getString("link_copied"))
        }
    }

    override fun setStoryReaction(reaction: StoryReactionModel) {
        val story = _state.value.currentStory ?: return
        scope.launch {
            val success = storyRepository.setStoryReaction(
                chatId = story.posterChatId,
                storyId = story.id,
                reaction = reaction
            )
            if (!success) {
                _state.value = _state.value.copy(inlineError = "Failed to send story reaction")
            } else {
                _state.value = _state.value.copy(
                    inlineError = null,
                    isStoryReactionPickerVisible = false,
                    isStoryReactionPickerLoading = false
                )
            }
        }
    }

    override fun setStoryMediaStretchEnabled(enabled: Boolean) {
        appPreferences.setStoryMediaStretchEnabled(enabled)
    }

    override fun dismissInlineVideo() {
        _state.value = _state.value.copy(showInlineVideo = false)
    }

    override fun showInlineVideo() {
        _state.value = _state.value.copy(showInlineVideo = true)
    }

    private fun openStoryAt(index: Int) {
        val current = _state.value
        val item = current.viewerItems.getOrNull(index) ?: return
        storyLoadJob?.cancel()
        storyRefreshJob?.cancel()
        val cachedPresentation = chatPresentationCache[item.chatId]
        _state.value = current.copy(
            viewerIndex = index,
            chatId = item.chatId,
            chatTitle = cachedPresentation?.title
                ?: if (item.chatId == current.chatId) current.chatTitle else "",
            chatAvatarPath = cachedPresentation?.avatarPath
                ?: if (item.chatId == current.chatId) current.chatAvatarPath else null,
            currentStory = null,
            isLoading = true,
            canManageStories = cachedPresentation?.canManageStories ?: false,
            composerMode = StoryComposerMode.CREATE,
            editingStoryId = null,
            inlineError = null,
            isSubmitting = false,
            isStoryStatisticsVisible = false,
            isStoryStatisticsLoading = false,
            storyStatistics = null,
            isStoryInteractionsVisible = false,
            isStoryInteractionsLoading = false,
            storyInteractionsPage = null,
            isStoryReactionPickerVisible = false,
            isStoryReactionPickerLoading = false,
            showInlineVideo = false,
            showStoryMediaLoadingMessage = false
        )
        syncStoryMediaLoadingMessage()
        current.currentStory?.let { previousStory ->
            scope.launch {
                storyRepository.closeStory(previousStory.posterChatId, previousStory.id)
            }
        }
        storyLoadJob = scope.launch {
            val story = loadStory(item)
            val chatPresentation = resolveChatPresentation(item.chatId)
            _state.value = _state.value.copy(
                chatId = item.chatId,
                chatTitle = chatPresentation.title,
                chatAvatarPath = chatPresentation.avatarPath,
                viewerIndex = index,
                currentStory = story,
                isLoading = story.requiresMediaRefresh(),
                canManageStories = chatPresentation.canManageStories,
                inlineError = if (story == null) "Unable to load story" else null,
                showInlineVideo = false,
                showStoryMediaLoadingMessage = false
            )
            syncStoryMediaLoadingMessage()
            scheduleStoryRefreshIfNeeded(item, story)
        }
    }

    private suspend fun loadStory(item: StoryViewerUiModel): StoryModel? {
        val story = storyRepository.getStory(item.chatId, item.storyId)
        if (story != null) {
            Log.d(
                TAG,
                "loadStory chatId=${item.chatId} storyId=${item.storyId} success mediaType=${story.media.type} path=${story.media.path} preview=${story.media.previewPath} minithumbnail=${story.media.minithumbnail != null}"
            )
            storyRepository.openStory(item.chatId, item.storyId)
        } else {
            Log.d(TAG, "loadStory chatId=${item.chatId} storyId=${item.storyId} returned null")
        }
        return story
    }

    private fun scheduleStoryRefreshIfNeeded(item: StoryViewerUiModel, story: StoryModel?) {
        if (!story.requiresMediaRefresh()) return
        storyRefreshJob?.cancel()
        storyRefreshJob = scope.launch {
            Log.d(TAG, "scheduleStoryRefresh chatId=${item.chatId} storyId=${item.storyId}")
            val delays = longArrayOf(150L, 350L, 700L, 1200L, 1800L, 2600L)
            for ((attempt, waitMs) in delays.withIndex()) {
                delay(waitMs)
                val current = _state.value
                if (
                    current.mode != StoriesHostComponent.Mode.Viewer ||
                    current.chatId != item.chatId ||
                    current.viewerItems.getOrNull(current.viewerIndex)?.storyId != item.storyId
                ) {
                    Log.d(
                        TAG,
                        "scheduleStoryRefresh cancelled due to story switch chatId=${item.chatId} storyId=${item.storyId}"
                    )
                    return@launch
                }
                val refreshed = storyRepository.getStory(item.chatId, item.storyId)
                Log.d(
                    TAG,
                    "storyRefresh attempt=${attempt + 1} chatId=${item.chatId} storyId=${item.storyId} path=${refreshed?.media?.path} preview=${refreshed?.media?.previewPath} mini=${refreshed?.media?.minithumbnail != null}"
                )
                if (refreshed != null) {
                    _state.value = _state.value.copy(
                        currentStory = refreshed,
                        isLoading = refreshed.requiresMediaRefresh(),
                        inlineError = null,
                        showStoryMediaLoadingMessage = false
                    )
                    syncStoryMediaLoadingMessage()
                }
                if (refreshed?.requiresMediaRefresh() == false) {
                    return@launch
                }
            }
        }
    }

    private fun syncStoryMediaLoadingMessage(disableOnly: Boolean = false) {
        val current = _state.value
        val currentItem = current.viewerItems.getOrNull(current.viewerIndex)
        val shouldTrack = !disableOnly &&
                current.mode == StoriesHostComponent.Mode.Viewer &&
                current.isLoading &&
                currentItem != null &&
                current.currentStory.requiresMediaRefresh()

        if (!shouldTrack) {
            storyMediaLoadingMessageJob?.cancel()
            storyMediaLoadingMessageJob = null
            storyMediaLoadingMessageKey = null
            if (current.showStoryMediaLoadingMessage) {
                _state.value = current.copy(showStoryMediaLoadingMessage = false)
            }
            return
        }

        val loadingKey = currentItem.chatId to currentItem.storyId
        if (storyMediaLoadingMessageKey == loadingKey && storyMediaLoadingMessageJob?.isActive == true) {
            return
        }

        storyMediaLoadingMessageJob?.cancel()
        storyMediaLoadingMessageKey = loadingKey
        if (current.showStoryMediaLoadingMessage) {
            _state.value = current.copy(showStoryMediaLoadingMessage = false)
        }
        storyMediaLoadingMessageJob = scope.launch {
            delay(60_000)
            val latest = _state.value
            val latestItem = latest.viewerItems.getOrNull(latest.viewerIndex)
            if (
                latest.mode == StoriesHostComponent.Mode.Viewer &&
                latest.isLoading &&
                latestItem?.chatId == loadingKey.first &&
                latestItem.storyId == loadingKey.second &&
                latest.currentStory.requiresMediaRefresh()
            ) {
                _state.value = latest.copy(showStoryMediaLoadingMessage = true)
            }
        }
    }

    private fun StoryModel?.requiresMediaRefresh(): Boolean {
        return this != null &&
                media.path.isNullOrBlank() &&
                media.previewPath.isNullOrBlank()
    }

    private suspend fun resolveChatPresentation(chatId: Long): ChatPresentation {
        chatPresentationCache[chatId]?.let { return it }

        val presentation = ChatPresentation(
            title = resolveChatTitle(chatId),
            avatarPath = resolveChatAvatar(chatId),
            canManageStories = canManageStories(chatId)
        )
        chatPresentationCache[chatId] = presentation
        return presentation
    }

    private suspend fun resolveChatTitle(chatId: Long): String {
        return chatListRepository.getChatById(chatId)?.title ?: chatId.toString()
    }

    private suspend fun resolveChatAvatar(chatId: Long): String? {
        return chatListRepository.getChatById(chatId)?.avatarPath
    }

    private suspend fun canManageStories(chatId: Long): Boolean {
        val currentUserId = _state.value.currentUserId
            ?: userRepository.currentUserFlow.value?.id
            ?: userRepository.getMe().id
        val chat = chatListRepository.getChatById(chatId)
        return currentUserId == chatId || chat?.isAdmin == true
    }

    private suspend fun resolvePublicStoryUsername(chatId: Long): String? {
        val chat = chatListRepository.getChatById(chatId)
        val chatUsername = chat?.username?.takeIf { it.isNotBlank() }
            ?: chat?.usernames?.activeUsernames?.firstOrNull { it.isNotBlank() }
        if (chatUsername != null) return chatUsername

        val user = userRepository.getUser(chatId)
        return user?.username?.takeIf { it.isNotBlank() }
            ?: user?.usernames?.activeUsernames?.firstOrNull { it.isNotBlank() }
    }

    private suspend fun enrichStoryInteractions(
        page: StoryInteractionPageModel
    ): StoryInteractionPageModel {
        val enrichedInteractions = mutableListOf<StoryInteractionModel>()
        for (interaction in page.interactions) {
            enrichedInteractions += enrichStoryInteraction(interaction)
        }
        return page.copy(
            interactions = enrichedInteractions
        )
    }

    private suspend fun enrichStoryInteraction(
        interaction: StoryInteractionModel
    ): StoryInteractionModel {
        return when (interaction.actorType) {
            StoryInteractionActorType.USER -> {
                val user = userRepository.getUser(interaction.actorId)
                val fullName = listOfNotNull(user?.firstName, user?.lastName)
                    .joinToString(" ")
                    .trim()
                val fallbackTitle = user?.username
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "@$it" }
                    ?: interaction.actorId.toString()
                interaction.copy(
                    actorTitle = fullName.ifBlank { interaction.actorTitle ?: fallbackTitle },
                    actorAvatarPath = user?.avatarPath
                )
            }

            StoryInteractionActorType.CHAT -> {
                val chat = chatListRepository.getChatById(interaction.actorId)
                interaction.copy(
                    actorTitle = chat?.title?.ifBlank { interaction.actorId.toString() }
                        ?: interaction.actorId.toString(),
                    actorAvatarPath = chat?.avatarPath
                )
            }
        }
    }

    private fun inferMediaType(sourcePath: String?): StoryMediaType {
        val normalized = sourcePath.orEmpty().lowercase()
        return if (
            normalized.endsWith(".mp4") ||
            normalized.endsWith(".mov") ||
            normalized.endsWith(".webm") ||
            normalized.endsWith(".mkv")
        ) {
            StoryMediaType.VIDEO
        } else {
            StoryMediaType.PHOTO
        }
    }

    private suspend fun resolveAudienceUsers(
        userIds: List<Long>,
        contacts: List<UserModel>
    ): List<UserModel> {
        val contactsById = contacts.associateBy(UserModel::id)
        return userIds.mapNotNull { userId ->
            contactsById[userId] ?: userRepository.getUser(userId)
        }
    }

    private fun mergeAudienceUsers(
        primary: List<UserModel>,
        secondary: List<UserModel>
    ): List<UserModel> {
        return (secondary + primary)
            .distinctBy(UserModel::id)
    }

    private fun createDefaultState(): StoriesHostComponent.State {
        return StoriesHostComponent.State(
            currentUserId = userRepository.currentUserFlow.value?.id,
            isPremiumUser = userRepository.currentUserFlow.value?.isPremium == true,
            stealthMode = storyRepository.stealthMode.value,
            storyOptions = storyRepository.storyOptions.value,
            isStoryMediaStretchEnabled = appPreferences.storyMediaStretchEnabled.value
        )
    }

    private fun reopenStory(
        chatId: Long,
        storyId: Int,
        viewerSource: StoryViewerSource,
        activeListType: StoryListType
    ) {
        when (viewerSource) {
            StoryViewerSource.ACTIVE -> openChatStories(chatId, storyId, activeListType)
            StoryViewerSource.PROFILE -> openProfileStories(chatId, storyId)
            StoryViewerSource.PROFILE_ARCHIVE -> openProfileStoryArchive(chatId, storyId)
            StoryViewerSource.ALBUM -> openChatStories(chatId, storyId, activeListType)
        }
    }

    companion object {
        private const val TAG = "StoriesHostDiag"
        private const val STORY_INTERACTIONS_PAGE_SIZE = 50
        private const val PROFILE_STORIES_PAGE_SIZE = 50
    }
}

internal fun createComposerDraft(
    preferredMediaType: StoryMediaType? = null,
    initialSourcePath: String? = null,
    initialCaption: String = "",
    widgetLink: String? = null
): StoryComposerDraftModel {
    val mediaItems = initialSourcePath
        ?.takeIf { it.isNotBlank() }
        ?.let { path ->
            listOf(
                StoryComposerMediaItemModel(
                    sourcePath = path,
                    mediaType = preferredMediaType ?: inferStoryComposerMediaType(path)
                )
            )
        }
        .orEmpty()
    return StoryComposerDraftModel(
        mediaItems = mediaItems,
        caption = initialCaption,
        widgetLink = widgetLink
    )
}

internal fun createEditComposerDraft(
    story: StoryModel,
    mediaPath: String
): StoryComposerDraftModel {
    return StoryComposerDraftModel(
        mediaItems = listOf(
            StoryComposerMediaItemModel(
                sourcePath = mediaPath,
                mediaType = story.media.type
            )
        ),
        caption = story.caption,
        privacy = checkNotNull(story.privacy),
        widgetLink = story.linkUrls.firstOrNull()
    )
}

internal fun resolveStoryEditableMediaPath(story: StoryModel?): String? {
    return story?.media?.path?.takeIf { it.isNotBlank() }
}

private fun restoreViewerState(state: StoriesHostComponent.State): StoriesHostComponent.State {
    return state.copy(
        mode = StoriesHostComponent.Mode.Viewer,
        isLoading = false,
        composerMode = StoryComposerMode.CREATE,
        editingStoryId = null,
        audiencePicker = StoryAudiencePickerState(),
        postCapability = null,
        inlineError = null,
        isSubmitting = false,
        isStoryStatisticsVisible = false,
        isStoryStatisticsLoading = false,
        storyStatistics = null,
        isStoryInteractionsVisible = false,
        isStoryInteractionsLoading = false,
        storyInteractionsPage = null,
        isStoryReactionPickerVisible = false,
        isStoryReactionPickerLoading = false,
        showMediaPicker = false,
        showCamera = false
    )
}

private fun StoryInteractionPageModel.mergeWith(
    nextPage: StoryInteractionPageModel
): StoryInteractionPageModel {
    return copy(
        totalCount = nextPage.totalCount,
        totalForwardCount = nextPage.totalForwardCount,
        totalReactionCount = nextPage.totalReactionCount,
        interactions = interactions + nextPage.interactions,
        nextOffset = nextPage.nextOffset
    )
}

internal sealed class StorySaveOutcome {
    data class Created(
        val story: StoryModel,
        val message: String? = null
    ) : StorySaveOutcome()

    data class Edited(val storyId: Int) : StorySaveOutcome()
    data class Failed(val message: String) : StorySaveOutcome()
}

internal suspend fun saveStoryDraft(
    storyRepository: StoryRepository,
    stringProvider: StringProvider,
    chatId: Long,
    composerMode: StoryComposerMode,
    editingStoryId: Int?,
    draft: StoryComposerDraftModel
): StorySaveOutcome {
    return when (composerMode) {
        StoryComposerMode.CREATE -> {
            val mediaItems = draft.mediaItems
            if (mediaItems.isEmpty()) {
                StorySaveOutcome.Failed(stringProvider.getString("story_validation_pick_media"))
            } else {
                var lastStory: StoryModel? = null
                var createdCount = 0
                for (mediaItem in mediaItems) {
                    when (val result =
                        storyRepository.postStory(chatId, draft.forSingleMedia(mediaItem))) {
                        is StoryPostResultModel.Success -> {
                            lastStory = result.story
                            createdCount += 1
                        }

                        is StoryPostResultModel.Failure -> {
                            if (lastStory != null && createdCount > 0) {
                                return StorySaveOutcome.Created(
                                    story = lastStory,
                                    message = "Published $createdCount stories. ${result.message.ifBlank { "Stopped before the remaining items" }}"
                                )
                            }
                            return StorySaveOutcome.Failed(
                                result.message.ifBlank { "Failed to publish story" }
                            )
                        }
                    }
                }

                StorySaveOutcome.Created(
                    story = lastStory ?: return StorySaveOutcome.Failed("Failed to publish story"),
                    message = if (createdCount > 1) {
                        "Published $createdCount stories"
                    } else {
                        null
                    }
                )
            }
        }

        StoryComposerMode.EDIT -> {
            val storyId = editingStoryId
                ?: return StorySaveOutcome.Failed("Missing story to edit")
            if (storyRepository.editStory(chatId, storyId, draft)) {
                StorySaveOutcome.Edited(storyId)
            } else {
                StorySaveOutcome.Failed("Failed to save story")
            }
        }
    }
}

internal fun updateStoryPrivacyMode(
    current: StoryPrivacySettingsModel,
    mode: StoryPrivacyUi
): StoryPrivacySettingsModel {
    return current.copy(
        mode = when (mode) {
            StoryPrivacyUi.EVERYONE -> StoryPrivacyMode.EVERYONE
            StoryPrivacyUi.CONTACTS -> StoryPrivacyMode.CONTACTS
            StoryPrivacyUi.CLOSE_FRIENDS -> StoryPrivacyMode.CLOSE_FRIENDS
            StoryPrivacyUi.SELECTED_USERS -> StoryPrivacyMode.SELECTED_USERS
        }
    )
}

internal fun toggleStoryAudienceUser(
    current: StoryPrivacySettingsModel,
    userId: Long,
    filterMode: StoryAudienceFilterMode
): StoryPrivacySettingsModel {
    return when (filterMode) {
        StoryAudienceFilterMode.SHOW_TO -> current.copy(
            selectedUserIds = current.selectedUserIds.toggleUserId(userId)
        )

        StoryAudienceFilterMode.HIDE_FROM -> current.copy(
            exceptUserIds = current.exceptUserIds.toggleUserId(userId)
        )
    }
}

internal fun clearStoryAudienceSelection(
    current: StoryPrivacySettingsModel,
    filterMode: StoryAudienceFilterMode
): StoryPrivacySettingsModel {
    return when (filterMode) {
        StoryAudienceFilterMode.SHOW_TO -> current.copy(selectedUserIds = emptyList())
        StoryAudienceFilterMode.HIDE_FROM -> current.copy(exceptUserIds = emptyList())
    }
}

internal fun resolveAudienceSelectionIds(
    privacy: StoryPrivacySettingsModel,
    filterMode: StoryAudienceFilterMode
): List<Long> {
    return when (filterMode) {
        StoryAudienceFilterMode.SHOW_TO -> privacy.selectedUserIds
        StoryAudienceFilterMode.HIDE_FROM -> privacy.exceptUserIds
    }
}

internal fun resolveStorySaveValidationError(
    stringProvider: StringProvider,
    draft: StoryComposerDraftModel,
    isPremiumUser: Boolean,
    storyOptions: StoryOptionsModel
): String? {
    if (!draft.isValid) {
        return stringProvider.getString("story_validation_pick_media")
    }

    val captionLengthMax = storyOptions.captionLengthMax
    if (captionLengthMax > 0 && draft.caption.length > captionLengthMax) {
        return stringProvider.getString("story_validation_caption_too_long", captionLengthMax)
    }

    if (
        draft.privacy.mode == StoryPrivacyMode.SELECTED_USERS &&
        draft.privacy.selectedUserIds.isEmpty()
    ) {
        return stringProvider.getString("story_validation_selected_users_required")
    }

    if (!draft.widgetLink.isNullOrBlank() && (!isPremiumUser || storyOptions.linkAreaCountMax <= 0)) {
        return stringProvider.getString("story_validation_links_premium")
    }

    return null
}

private fun StringProvider.getCompactStoryDuration(seconds: Int): String {
    if (seconds <= 0) {
        return getQuantityString("story_duration_compact_minutes", 0, 0)
    }

    return if (seconds % 3600 == 0) {
        val hours = seconds / 3600
        getQuantityString("story_duration_compact_hours", hours, hours)
    } else {
        val minutes = (seconds / 60).coerceAtLeast(1)
        getQuantityString("story_duration_compact_minutes", minutes, minutes)
    }
}

private fun inferStoryComposerMediaType(sourcePath: String?): StoryMediaType {
    val normalized = sourcePath.orEmpty().lowercase()
    return if (
        normalized.endsWith(".mp4") ||
        normalized.endsWith(".mov") ||
        normalized.endsWith(".webm") ||
        normalized.endsWith(".mkv")
    ) {
        StoryMediaType.VIDEO
    } else {
        StoryMediaType.PHOTO
    }
}

private fun StoryComposerDraftModel.replaceCurrentMedia(
    mediaItem: StoryComposerMediaItemModel
): StoryComposerDraftModel {
    if (mediaItems.isEmpty()) {
        return copy(
            mediaItems = listOf(mediaItem),
            selectedMediaIndex = 0
        )
    }

    val safeIndex = selectedMediaIndex.coerceIn(0, mediaItems.lastIndex)
    val updatedItems = mediaItems.toMutableList().apply {
        this[safeIndex] = mediaItem
    }
    return copy(
        mediaItems = updatedItems,
        selectedMediaIndex = safeIndex
    )
}

private fun StoryComposerDraftModel.replaceAllMedia(
    items: List<StoryComposerMediaItemModel>
): StoryComposerDraftModel {
    return copy(
        mediaItems = items,
        selectedMediaIndex = 0
    )
}

private fun StoryComposerDraftModel.selectMedia(index: Int): StoryComposerDraftModel {
    if (mediaItems.isEmpty()) return copy(selectedMediaIndex = 0)
    return copy(selectedMediaIndex = index.coerceIn(0, mediaItems.lastIndex))
}

private fun StoryComposerDraftModel.forSingleMedia(
    mediaItem: StoryComposerMediaItemModel
): StoryComposerDraftModel {
    return copy(
        mediaItems = listOf(mediaItem),
        selectedMediaIndex = 0
    )
}

private fun List<Long>.toggleUserId(userId: Long): List<Long> {
    return if (contains(userId)) {
        filterNot { it == userId }
    } else {
        this + userId
    }
}

internal fun buildViewerItems(activeStories: List<ActiveStoryListModel>): List<StoryViewerUiModel> {
    return activeStories.flatMap { activeStoriesForChat ->
        activeStoriesForChat.stories.map { storySummary ->
            StoryViewerUiModel(
                chatId = activeStoriesForChat.chatId,
                storyId = storySummary.storyId,
                date = storySummary.date
            )
        }
    }
}

internal fun resolveInitialViewerIndex(
    items: List<StoryViewerUiModel>,
    chatId: Long,
    storyId: Int?
): Int {
    if (items.isEmpty()) return 0

    val exactIndex =
        items.indexOfFirst { it.chatId == chatId && (storyId == null || it.storyId == storyId) }
    if (exactIndex >= 0) return exactIndex

    val chatIndex = items.indexOfFirst { it.chatId == chatId }
    return if (chatIndex >= 0) chatIndex else 0
}

private data class ChatPresentation(
    val title: String,
    val avatarPath: String?,
    val canManageStories: Boolean
)

