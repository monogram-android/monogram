package org.monogram.presentation.features.stories

import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.monogram.domain.models.stories.ActiveStoryListModel
import org.monogram.domain.models.stories.StoryComposerDraftModel
import org.monogram.domain.models.stories.StoryListType
import org.monogram.domain.models.stories.StoryMediaType
import org.monogram.domain.models.stories.StoryModel
import org.monogram.domain.models.stories.StoryPrivacyMode
import org.monogram.domain.models.stories.StoryPrivacySettingsModel
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
    private val messageDisplayer = container.utils.messageDisplayer()
    private val scope = componentScope
    private var hasLoadedActiveStories = false
    private var storyLoadJob: Job? = null
    private var storyRefreshJob: Job? = null
    private val chatPresentationCache = mutableMapOf<Long, ChatPresentation>()

    private val _state = MutableStateFlow(StoriesHostComponent.State())
    override val state = _state.asStateFlow()

    init {
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
            inlineError = null,
            showInlineVideo = false
        )
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
                showInlineVideo = false
            )
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
            inlineError = null,
            showInlineVideo = false
        )
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
                showInlineVideo = false
            )
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
        storyRefreshJob?.cancel()
        _state.value = _state.value.copy(
            mode = StoriesHostComponent.Mode.Composer,
            isLoading = true,
            chatId = chatId,
            chatTitle = "",
            chatAvatarPath = null,
            canManageStories = false,
            composerDraft = StoryComposerDraftModel(
                sourcePath = initialSourcePath.orEmpty(),
                mediaType = preferredMediaType ?: inferMediaType(initialSourcePath),
                caption = initialCaption,
                widgetLink = widgetLink
            ),
            postCapability = null,
            inlineError = null,
            showMediaPicker = initialSourcePath.isNullOrBlank(),
            showCamera = false,
            isSubmitting = false,
            showInlineVideo = false
        )
        Log.d(TAG, "composer placeholder shown chatId=$chatId")
        scope.launch {
            val capability = storyRepository.canPostStory(chatId)
            Log.d(TAG, "openComposer chatId=$chatId capability=$capability")
            _state.value = _state.value.copy(
                mode = StoriesHostComponent.Mode.Composer,
                isLoading = false,
                chatId = chatId,
                chatTitle = resolveChatTitle(chatId),
                chatAvatarPath = resolveChatAvatar(chatId),
                canManageStories = canManageStories(chatId),
                composerDraft = StoryComposerDraftModel(
                    sourcePath = initialSourcePath.orEmpty(),
                    mediaType = preferredMediaType ?: inferMediaType(initialSourcePath),
                    caption = initialCaption,
                    widgetLink = widgetLink
                ),
                postCapability = capability,
                inlineError = null,
                showMediaPicker = initialSourcePath.isNullOrBlank(),
                showCamera = false,
                isSubmitting = false,
                showInlineVideo = false
            )
        }
    }

    override fun dismiss() {
        storyLoadJob?.cancel()
        storyRefreshJob?.cancel()
        scope.launch {
            state.value.currentStory?.let {
                storyRepository.closeStory(it.posterChatId, it.id)
            }
        }
        _state.value = StoriesHostComponent.State()
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
        _state.value = _state.value.copy(
            composerDraft = _state.value.composerDraft.copy(
                sourcePath = path,
                mediaType = mediaType
            ),
            showMediaPicker = false,
            showCamera = false,
            inlineError = null
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

    override fun submitStory() {
        val current = _state.value
        val chatId = current.chatId ?: return
        if (!current.composerDraft.isValid) {
            _state.value = current.copy(inlineError = "Pick a photo or video first")
            return
        }

        _state.value = current.copy(isSubmitting = true, inlineError = null)
        scope.launch {
            Log.d(TAG, "submitStory chatId=$chatId mediaType=${current.composerDraft.mediaType}")
            when (val result = storyRepository.postStory(chatId, _state.value.composerDraft)) {
                is org.monogram.domain.models.stories.StoryPostResultModel.Success -> {
                    Log.d(TAG, "submitStory success chatId=$chatId storyId=${result.story.id}")
                    _state.value = StoriesHostComponent.State()
                    openChatStories(chatId, result.story.id)
                }

                is org.monogram.domain.models.stories.StoryPostResultModel.Failure -> {
                    Log.d(TAG, "submitStory failed chatId=$chatId message=${result.message}")
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        inlineError = result.message.ifBlank { "Failed to publish story" }
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
            inlineError = null,
            showInlineVideo = false
        )
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
                showInlineVideo = false
            )
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
                        inlineError = null
                    )
                }
                if (refreshed?.requiresMediaRefresh() == false) {
                    return@launch
                }
            }

            val current = _state.value
            if (
                current.mode == StoriesHostComponent.Mode.Viewer &&
                current.chatId == item.chatId &&
                current.viewerItems.getOrNull(current.viewerIndex)?.storyId == item.storyId &&
                current.currentStory.requiresMediaRefresh()
            ) {
                _state.value = current.copy(
                    isLoading = false,
                    inlineError = "Media is still loading"
                )
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

    companion object {
        private const val TAG = "StoriesHostDiag"
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
