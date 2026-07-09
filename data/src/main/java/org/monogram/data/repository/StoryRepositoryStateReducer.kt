package org.monogram.data.repository

import org.monogram.domain.models.stories.ActiveStoryListModel
import org.monogram.domain.models.stories.StoryListType
import org.monogram.domain.models.stories.StoryModel
import org.monogram.domain.models.stories.StoryPostResultModel
import org.monogram.domain.models.stories.StoryStealthModeModel

internal data class StoryKey(
    val chatId: Long,
    val storyId: Int
)

internal data class StoryRepositoryState(
    val activeStories: Map<StoryListType, List<ActiveStoryListModel>> = emptyMap(),
    val storyCache: Map<StoryKey, StoryModel> = emptyMap(),
    val storyListChatCounts: Map<StoryListType, Int> = emptyMap(),
    val stealthMode: StoryStealthModeModel = StoryStealthModeModel(),
    val lastPostResult: StoryPostResultModel? = null
)

internal object StoryRepositoryStateReducer {
    fun withActiveStories(
        state: StoryRepositoryState,
        active: ActiveStoryListModel
    ): StoryRepositoryState {
        val currentList = state.activeStories[active.listType].orEmpty()
            .filterNot { it.chatId == active.chatId }
        val updatedList = (currentList + active)
            .sortedWith(compareByDescending<ActiveStoryListModel> { it.order }.thenByDescending { it.chatId })
        return state.copy(
            activeStories = state.activeStories + (active.listType to updatedList),
            storyCache = state.storyCache + active.stories.associate { summary ->
                val existing = state.storyCache[StoryKey(active.chatId, summary.storyId)]
                StoryKey(active.chatId, summary.storyId) to (existing?.copy(isRead = summary.isRead)
                    ?: StoryModel(
                        id = summary.storyId,
                        posterChatId = active.chatId,
                        date = summary.date,
                        caption = "",
                        media = existing?.media
                            ?: org.monogram.domain.models.stories.StoryMediaModel(
                                type = org.monogram.domain.models.stories.StoryMediaType.PHOTO,
                                path = null,
                                previewPath = null
                            ),
                        privacy = existing?.privacy
                            ?: org.monogram.domain.models.stories.StoryPrivacySettingsModel(
                                mode = org.monogram.domain.models.stories.StoryPrivacyMode.EVERYONE
                            ),
                        isRead = summary.isRead
                    ))
            }
        )
    }

    fun withStory(
        state: StoryRepositoryState,
        story: StoryModel
    ): StoryRepositoryState {
        val key = StoryKey(story.posterChatId, story.id)
        val active = state.findActiveStories(story.posterChatId)
        val normalized = if (active != null) {
            story.copy(
                isRead = active.stories.firstOrNull { it.storyId == story.id }?.isRead
                    ?: story.isRead
            )
        } else {
            story
        }
        return state.copy(storyCache = state.storyCache + (key to normalized))
    }

    fun withStoryDeleted(
        state: StoryRepositoryState,
        chatId: Long,
        storyId: Int
    ): StoryRepositoryState {
        val newActiveStories = state.activeStories.mapValues { (_, list) ->
            list.mapNotNull { active ->
                if (active.chatId != chatId) {
                    active
                } else {
                    val remaining = active.stories.filterNot { it.storyId == storyId }
                    if (remaining.isEmpty()) {
                        null
                    } else {
                        active.copy(stories = remaining)
                    }
                }
            }
        }.filterValues { it.isNotEmpty() }
        return state.copy(
            activeStories = newActiveStories,
            storyCache = state.storyCache - StoryKey(chatId, storyId)
        )
    }

    fun withPostSucceeded(
        state: StoryRepositoryState,
        story: StoryModel,
        oldStoryId: Int
    ): StoryRepositoryState {
        val withoutOld = state.storyCache - StoryKey(story.posterChatId, oldStoryId)
        val withStory = withStory(state.copy(storyCache = withoutOld), story)
        return withStory.copy(lastPostResult = StoryPostResultModel.Success(story, oldStoryId))
    }

    fun withPostFailed(
        state: StoryRepositoryState,
        story: StoryModel?,
        message: String,
        capability: org.monogram.domain.models.stories.StoryPostCapabilityModel?
    ): StoryRepositoryState {
        val withStory = if (story != null) withStory(state, story) else state
        return withStory.copy(
            lastPostResult = StoryPostResultModel.Failure(
                story = story,
                message = message,
                capability = capability
            )
        )
    }

    fun withStoryListChatCount(
        state: StoryRepositoryState,
        listType: StoryListType,
        chatCount: Int
    ): StoryRepositoryState {
        return state.copy(storyListChatCounts = state.storyListChatCounts + (listType to chatCount))
    }

    fun withStealthMode(
        state: StoryRepositoryState,
        stealthMode: StoryStealthModeModel
    ): StoryRepositoryState {
        return state.copy(stealthMode = stealthMode)
    }

    fun clearLastPostResult(state: StoryRepositoryState): StoryRepositoryState {
        return state.copy(lastPostResult = null)
    }

    private fun StoryRepositoryState.findActiveStories(chatId: Long): ActiveStoryListModel? {
        return activeStories.values.asSequence()
            .flatMap(List<ActiveStoryListModel>::asSequence)
            .firstOrNull { it.chatId == chatId }
    }
}
