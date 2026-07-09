package org.monogram.domain.repository

import kotlinx.coroutines.flow.StateFlow
import org.monogram.domain.models.stories.ActiveStoryListModel
import org.monogram.domain.models.stories.StoryComposerDraftModel
import org.monogram.domain.models.stories.StoryListType
import org.monogram.domain.models.stories.StoryModel
import org.monogram.domain.models.stories.StoryPostCapabilityModel
import org.monogram.domain.models.stories.StoryPostResultModel
import org.monogram.domain.models.stories.StoryStealthModeModel

interface StoryRepository {
    val activeStories: StateFlow<Map<StoryListType, List<ActiveStoryListModel>>>
    val storyListChatCounts: StateFlow<Map<StoryListType, Int>>
    val stealthMode: StateFlow<StoryStealthModeModel>
    val lastPostResult: StateFlow<StoryPostResultModel?>

    suspend fun loadActiveStories(listType: StoryListType)
    suspend fun getChatActiveStories(chatId: Long): ActiveStoryListModel?
    suspend fun getStory(chatId: Long, storyId: Int, onlyLocal: Boolean = false): StoryModel?
    suspend fun getStoryAlbum(
        chatId: Long,
        albumId: Int,
        offset: Int = 0,
        limit: Int = 50
    ): List<StoryModel>

    suspend fun openStory(chatId: Long, storyId: Int)
    suspend fun closeStory(chatId: Long, storyId: Int)
    suspend fun canPostStory(chatId: Long): StoryPostCapabilityModel
    suspend fun postStory(chatId: Long, draft: StoryComposerDraftModel): StoryPostResultModel
    suspend fun editStory(chatId: Long, storyId: Int, draft: StoryComposerDraftModel): Boolean
    suspend fun deleteStory(chatId: Long, storyId: Int): Boolean
    suspend fun setChatActiveStoriesList(chatId: Long, listType: StoryListType?): Boolean
    fun clearLastPostResult()
}
