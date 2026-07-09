package org.monogram.presentation.features.stories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.stories.ActiveStoryListModel
import org.monogram.domain.models.stories.StoryListType
import org.monogram.domain.models.stories.StoryMediaModel
import org.monogram.domain.models.stories.StoryMediaType
import org.monogram.domain.models.stories.StoryModel
import org.monogram.domain.models.stories.StoryPostCapabilityModel
import org.monogram.domain.models.stories.StoryPrivacyMode
import org.monogram.domain.models.stories.StoryPrivacySettingsModel
import org.monogram.domain.models.stories.StorySummaryModel

class StoriesHostContentTest {
    @Test
    fun `resolveStoryAutoAdvanceDurationMs uses default duration for photos without metadata`() {
        assertEquals(5_500, resolveStoryAutoAdvanceDurationMs(story()))
    }

    @Test
    fun `resolveStoryAutoAdvanceDurationMs adds caption bonus`() {
        assertEquals(7_000, resolveStoryAutoAdvanceDurationMs(story(caption = "Hello")))
    }

    @Test
    fun `resolveStoryAutoAdvanceDurationMs respects explicit media duration`() {
        assertEquals(
            3_200,
            resolveStoryAutoAdvanceDurationMs(
                story(durationSeconds = 3.2)
            )
        )
    }

    @Test
    fun `canPublishStory allows null and allowed capability`() {
        assertTrue(canPublishStory(null))
        assertTrue(canPublishStory(StoryPostCapabilityModel.Allowed(remainingCount = 2)))
    }

    @Test
    fun `canPublishStory blocks premium and limit capabilities`() {
        assertFalse(canPublishStory(StoryPostCapabilityModel.PremiumNeeded))
        assertFalse(canPublishStory(StoryPostCapabilityModel.ActiveStoryLimitExceeded))
    }

    @Test
    fun `buildViewerItems flattens stories across chats preserving order`() {
        val items = buildViewerItems(
            listOf(
                activeStories(chatId = 10L, storyIds = listOf(1, 2)),
                activeStories(chatId = 20L, storyIds = listOf(3))
            )
        )

        assertEquals(
            listOf(
                StoryViewerUiModel(chatId = 10L, storyId = 1, date = 1001),
                StoryViewerUiModel(chatId = 10L, storyId = 2, date = 1002),
                StoryViewerUiModel(chatId = 20L, storyId = 3, date = 1003)
            ),
            items
        )
    }

    @Test
    fun `resolveInitialViewerIndex prefers exact story then first story of chat`() {
        val items = listOf(
            StoryViewerUiModel(chatId = 10L, storyId = 1, date = 1001),
            StoryViewerUiModel(chatId = 10L, storyId = 2, date = 1002),
            StoryViewerUiModel(chatId = 20L, storyId = 3, date = 1003)
        )

        assertEquals(1, resolveInitialViewerIndex(items, chatId = 10L, storyId = 2))
        assertEquals(2, resolveInitialViewerIndex(items, chatId = 20L, storyId = 99))
        assertEquals(0, resolveInitialViewerIndex(items, chatId = 30L, storyId = null))
    }

    @Test
    fun `shouldRestartCurrentStoryFromPreviousTap restarts only after thirty percent`() {
        assertFalse(shouldRestartCurrentStoryFromPreviousTap(0.3f))
        assertTrue(shouldRestartCurrentStoryFromPreviousTap(0.31f))
    }

    private fun story(
        caption: String = "",
        durationSeconds: Double? = null
    ): StoryModel {
        return StoryModel(
            id = 1,
            posterChatId = 1L,
            date = 0,
            caption = caption,
            media = StoryMediaModel(
                type = StoryMediaType.PHOTO,
                path = "/tmp/story.jpg",
                previewPath = null,
                durationSeconds = durationSeconds
            ),
            privacy = StoryPrivacySettingsModel(mode = StoryPrivacyMode.EVERYONE)
        )
    }

    private fun activeStories(chatId: Long, storyIds: List<Int>): ActiveStoryListModel {
        return ActiveStoryListModel(
            chatId = chatId,
            listType = StoryListType.MAIN,
            order = 0,
            canBeArchived = false,
            maxReadStoryId = 0,
            stories = storyIds.map { storyId ->
                StorySummaryModel(
                    storyId = storyId,
                    date = 1000 + storyId,
                    isForCloseFriends = false,
                    isLive = false,
                    isRead = false
                )
            }
        )
    }
}
