package org.monogram.presentation.features.stories

import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.monogram.domain.models.stories.ActiveStoryListModel
import org.monogram.domain.models.stories.StoryComposerDraftModel
import org.monogram.domain.models.stories.StoryComposerMediaItemModel
import org.monogram.domain.models.stories.StoryInteractionActorType
import org.monogram.domain.models.stories.StoryInteractionModel
import org.monogram.domain.models.stories.StoryInteractionPageModel
import org.monogram.domain.models.stories.StoryListType
import org.monogram.domain.models.stories.StoryMediaType
import org.monogram.domain.models.stories.StoryModel
import org.monogram.domain.models.stories.StoryPostResultModel
import org.monogram.domain.models.stories.StoryPrivacyMode
import org.monogram.domain.models.stories.StoryPrivacySettingsModel
import org.monogram.domain.models.stories.StoryReactionModel
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.AuthRepository
import org.monogram.domain.repository.AuthStep
import org.monogram.domain.repository.ChatListRepository
import org.monogram.domain.repository.StoryRepository
import org.monogram.domain.repository.UserRepository
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext

class DefaultStoriesHostComponent(
    context: AppComponentContext
) : StoriesHostComponent, AppComponentContext by context {
    private val authRepository: AuthRepository = container.repositories.authRepository
    private val storyRepository: StoryRepository = container.repositories.storyRepository
    private val chatListRepository: ChatListRepository = container.repositories.chatListRepository
    private val userRepository: UserRepository = container.repositories.userRepository
    private val appPreferences: AppPreferencesProvider =
        container.preferences.appPreferencesProvider
    private val messageDisplayer = container.utils.messageDisplayer()
    private val clipManager = container.utils.clipManager
    private val externalNavigator = container.utils.externalNavigator()
    private val stringProvider = container.utils.stringProvider()
    private val scope = componentScope
    private var hasLoadedActiveStories = false
    private var storyLoadJob: Job? = null
    private var storyRefreshJob: Job? = null
    private var storyMediaLoadingMessageJob: Job? = null
    private var storyMediaLoadingMessageKey: Pair<Long, Int>? = null
    private val chatPresentationCache = mutableMapOf<Long, ChatPresentation>()

    private val _state = MutableStateFlow(createDefaultState())
    override val state = _state.asStateFlow()

    init {
        scope.launch {
            appPreferences.storyMediaStretchEnabled.collect { enabled ->
                _state.value = _state.value.copy(isStoryMediaStretchEnabled = enabled)
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

    override fun openChatStories(chatId: Long, storyId: Int?, listType: StoryListType) {
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
            activeListType = listType,
            canManageStories = false,
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
            activeListType = StoryListType.MAIN,
            canManageStories = false,
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
                canManageStories = canManageStories(chatId),
                inlineError = null,
                showInlineVideo = false,
                showStoryMediaLoadingMessage = false
            )
            syncStoryMediaLoadingMessage()
            scheduleStoryRefreshIfNeeded(items.first(), stories.first())
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
        if (!story.canBeEdited || mediaPath == null) {
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
            postCapability = null,
            inlineError = null,
            isSubmitting = false,
            isStoryStatisticsVisible = false,
            isStoryStatisticsLoading = false,
            storyStatistics = null,
            isStoryInteractionsVisible = false,
            isStoryInteractionsLoading = false,
            storyInteractionsPage = null,
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
            _state.value = _state.value.copy(
                mode = StoriesHostComponent.Mode.Composer,
                isLoading = false,
                chatId = chatId,
                chatTitle = resolveChatTitle(chatId),
                chatAvatarPath = resolveChatAvatar(chatId),
                canManageStories = canManageStories(chatId),
                composerMode = composerMode,
                editingStoryId = editingStoryId,
                composerDraft = draft,
                postCapability = capability,
                inlineError = null,
                showMediaPicker = !draft.isValid,
                showCamera = false,
                isSubmitting = false,
                showInlineVideo = false,
                showStoryMediaLoadingMessage = false
            )
        }
    }

    override fun dismiss() {
        storyLoadJob?.cancel()
        storyRefreshJob?.cancel()
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
        _state.value = _state.value.copy(
            composerDraft = _state.value.composerDraft.copy(
                privacy = StoryPrivacySettingsModel(
                    mode = when (mode) {
                        StoryPrivacyUi.EVERYONE -> StoryPrivacyMode.EVERYONE
                        StoryPrivacyUi.CONTACTS -> StoryPrivacyMode.CONTACTS
                        StoryPrivacyUi.CLOSE_FRIENDS -> StoryPrivacyMode.CLOSE_FRIENDS
                    }
                )
            )
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
        if (!current.composerDraft.isValid) {
            _state.value = current.copy(inlineError = "Pick a photo or video first")
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
                    _state.value = restoreViewerState(
                        _state.value.copy(
                            isSubmitting = false,
                            composerMode = StoryComposerMode.CREATE,
                            editingStoryId = null
                        )
                    )
                    openChatStories(chatId, result.storyId, listType)
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
            storyInteractionsPage = null
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

    override fun openStoryLink(url: String) {
        externalNavigator.openUrl(url)
    }

    override fun copyStoryLink(url: String) {
        clipManager.copyToClipboard("story_link", url)
        messageDisplayer.show(stringProvider.getString("link_copied"))
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
        val me = userRepository.getMe()
        val chat = chatListRepository.getChatById(chatId)
        return me?.id == chatId || chat?.isAdmin == true
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

    private fun createDefaultState(): StoriesHostComponent.State {
        return StoriesHostComponent.State(
            isStoryMediaStretchEnabled = appPreferences.storyMediaStretchEnabled.value
        )
    }

    companion object {
        private const val TAG = "StoriesHostDiag"
        private const val STORY_INTERACTIONS_PAGE_SIZE = 50
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
        privacy = story.privacy,
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
        postCapability = null,
        inlineError = null,
        isSubmitting = false,
        isStoryStatisticsVisible = false,
        isStoryStatisticsLoading = false,
        storyStatistics = null,
        isStoryInteractionsVisible = false,
        isStoryInteractionsLoading = false,
        storyInteractionsPage = null,
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
    chatId: Long,
    composerMode: StoryComposerMode,
    editingStoryId: Int?,
    draft: StoryComposerDraftModel
): StorySaveOutcome {
    return when (composerMode) {
        StoryComposerMode.CREATE -> {
            val mediaItems = draft.mediaItems
            if (mediaItems.isEmpty()) {
                StorySaveOutcome.Failed("Pick a photo or video first")
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
