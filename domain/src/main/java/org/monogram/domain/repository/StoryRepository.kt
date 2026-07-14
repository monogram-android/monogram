package org.monogram.domain.repository

import kotlinx.coroutines.flow.StateFlow
import org.monogram.domain.models.stories.ActiveStoryListModel
import org.monogram.domain.models.stories.StoryAvailableReactionsModel
import org.monogram.domain.models.stories.StoryComposerDraftModel
import org.monogram.domain.models.stories.StoryInteractionPageModel
import org.monogram.domain.models.stories.StoryListType
import org.monogram.domain.models.stories.StoryModel
import org.monogram.domain.models.stories.StoryOptionsModel
import org.monogram.domain.models.stories.StoryPageModel
import org.monogram.domain.models.stories.StoryPostCapabilityModel
import org.monogram.domain.models.stories.StoryPostResultModel
import org.monogram.domain.models.stories.StoryReactionModel
import org.monogram.domain.models.stories.StoryStatisticsModel
import org.monogram.domain.models.stories.StoryStealthModeModel

interface StoryRepository {
    val activeStories: StateFlow<Map<StoryListType, List<ActiveStoryListModel>>>
    val storyListChatCounts: StateFlow<Map<StoryListType, Int>>
    val stealthMode: StateFlow<StoryStealthModeModel>
    val storyOptions: StateFlow<StoryOptionsModel>
    val lastPostResult: StateFlow<StoryPostResultModel?>

    suspend fun loadActiveStories(listType: StoryListType)
    suspend fun refreshStoryOptions()
    suspend fun getChatActiveStories(chatId: Long): ActiveStoryListModel?
    suspend fun getStory(chatId: Long, storyId: Int, onlyLocal: Boolean = false): StoryModel?
    suspend fun getStoryAlbum(
        chatId: Long,
        albumId: Int,
        offset: Int = 0,
        limit: Int = 50
    ): List<StoryModel>

    suspend fun getChatPostedToChatPageStories(
        chatId: Long,
        fromStoryId: Int = 0,
        limit: Int = 50
    ): StoryPageModel?

    suspend fun getChatArchivedStories(
        chatId: Long,
        fromStoryId: Int = 0,
        limit: Int = 50
    ): StoryPageModel?

    suspend fun openStory(chatId: Long, storyId: Int)
    suspend fun closeStory(chatId: Long, storyId: Int)
    suspend fun activateStealthMode(): Boolean
    suspend fun canPostStory(chatId: Long): StoryPostCapabilityModel
    suspend fun getStoryStatistics(
        chatId: Long,
        storyId: Int,
        isDark: Boolean
    ): StoryStatisticsModel?
    suspend fun getStoryAvailableReactions(rowSize: Int = 8): StoryAvailableReactionsModel?

    suspend fun setStoryReaction(
        chatId: Long,
        storyId: Int,
        reaction: StoryReactionModel
    ): Boolean

    suspend fun getStoryInteractions(
        storyId: Int,
        offset: String,
        limit: Int,
        query: String = "",
        onlyContacts: Boolean = false,
        preferForwards: Boolean = false,
        preferWithReaction: Boolean = false
    ): StoryInteractionPageModel?
    suspend fun postStory(chatId: Long, draft: StoryComposerDraftModel): StoryPostResultModel
    suspend fun editStory(chatId: Long, storyId: Int, draft: StoryComposerDraftModel): Boolean
    suspend fun deleteStory(chatId: Long, storyId: Int): Boolean
    suspend fun toggleStoryPostedToChatPage(
        chatId: Long,
        storyId: Int,
        isPostedToChatPage: Boolean
    ): Boolean
    suspend fun setChatActiveStoriesList(chatId: Long, listType: StoryListType?): Boolean
    fun clearLastPostResult()
}
