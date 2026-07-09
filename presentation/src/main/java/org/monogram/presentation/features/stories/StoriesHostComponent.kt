package org.monogram.presentation.features.stories

import kotlinx.coroutines.flow.StateFlow
import org.monogram.domain.models.stories.ActiveStoryListModel
import org.monogram.domain.models.stories.StoryComposerDraftModel
import org.monogram.domain.models.stories.StoryListType
import org.monogram.domain.models.stories.StoryMediaType
import org.monogram.domain.models.stories.StoryModel
import org.monogram.domain.models.stories.StoryPostCapabilityModel

interface StoriesHostComponent {
    val state: StateFlow<State>

    fun openChatStories(
        chatId: Long,
        storyId: Int? = null,
        listType: StoryListType = StoryListType.MAIN
    )

    fun openStoryAlbum(chatId: Long, albumId: Int)
    fun openComposer(
        chatId: Long,
        preferredMediaType: StoryMediaType? = null,
        initialSourcePath: String? = null,
        initialCaption: String = "",
        widgetLink: String? = null
    )

    fun dismiss()
    fun nextStory()
    fun previousStory()
    fun openMediaPicker()
    fun dismissMediaPicker()
    fun showCamera()
    fun dismissCamera()
    fun attachMedia(path: String, mediaType: StoryMediaType)
    fun updateCaption(caption: String)
    fun updatePrivacy(mode: StoryPrivacyUi)
    fun updateActivePeriod(seconds: Int)
    fun updateProtectContent(protectContent: Boolean)
    fun updateKeepOnProfile(keepOnProfile: Boolean)
    fun submitStory()
    fun deleteCurrentStory()
    fun moveCurrentStoryToArchive()
    fun restoreCurrentStoryFromArchive()
    fun dismissInlineVideo()
    fun showInlineVideo()

    data class State(
        val mode: Mode = Mode.Hidden,
        val isLoading: Boolean = false,
        val chatId: Long? = null,
        val chatTitle: String = "",
        val chatAvatarPath: String? = null,
        val viewerItems: List<StoryViewerUiModel> = emptyList(),
        val viewerIndex: Int = 0,
        val currentStory: StoryModel? = null,
        val activeListType: StoryListType = StoryListType.MAIN,
        val canManageStories: Boolean = false,
        val composerDraft: StoryComposerDraftModel = StoryComposerDraftModel(),
        val postCapability: StoryPostCapabilityModel? = null,
        val inlineError: String? = null,
        val isSubmitting: Boolean = false,
        val showMediaPicker: Boolean = false,
        val showCamera: Boolean = false,
        val showInlineVideo: Boolean = false
    ) {
        val isVisible: Boolean
            get() = mode != Mode.Hidden

        val canGoPrevious: Boolean
            get() = viewerIndex > 0

        val canGoNext: Boolean
            get() = viewerIndex >= 0 && viewerIndex < viewerItems.lastIndex
    }

    enum class Mode {
        Hidden,
        Viewer,
        Composer
    }
}

data class StoryViewerUiModel(
    val chatId: Long,
    val storyId: Int,
    val date: Int
)

enum class StoryPrivacyUi {
    EVERYONE,
    CONTACTS,
    CLOSE_FRIENDS
}

data class StoryStripItemUiModel(
    val chatId: Long,
    val title: String,
    val avatarPath: String?,
    val activeStories: ActiveStoryListModel
)
