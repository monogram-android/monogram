package org.monogram.presentation.features.chats.list

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.ChatModel

class FolderChatsNormalizationTest {
    @Test
    fun `normalizeFolderChats removes duplicates without reordering first items`() {
        val first = chat(1L, order = 30L)
        val second = chat(2L, order = 20L)
        val duplicateFirst = chat(1L, order = 10L)

        val result = normalizeFolderChats(listOf(first, second, duplicateFirst))

        assertEquals(listOf(first, second), result)
    }

    @Test
    fun `normalizeFolderChats returns same list instance when already unique`() {
        val source = listOf(chat(1L), chat(2L))

        val result = normalizeFolderChats(source)

        assertSame(source, result)
    }

    @Test
    fun `shouldRefreshStoriesForFolderUpdate ignores noisy changes for tracked story chats`() {
        val previous = listOf(
            chat(
                id = 1L,
                title = "before",
                unreadCount = 0,
                typingAction = null,
                userStatus = "offline"
            )
        )
        val updated = listOf(
            chat(
                id = 1L,
                title = "after",
                unreadCount = 7,
                typingAction = "typing",
                userStatus = "online"
            )
        )

        val result = shouldRefreshStoriesForFolderUpdate(
            previousChats = previous,
            updatedChats = updated,
            trackedStoryChatIds = setOf(1L)
        )

        assertFalse(result)
    }

    @Test
    fun `shouldRefreshStoriesForFolderUpdate reacts to archive state changes for tracked stories`() {
        val previous = listOf(chat(id = 1L, isArchived = false))
        val updated = listOf(chat(id = 1L, isArchived = true))

        val result = shouldRefreshStoriesForFolderUpdate(
            previousChats = previous,
            updatedChats = updated,
            trackedStoryChatIds = setOf(1L)
        )

        assertTrue(result)
    }

    @Test
    fun `shouldRefreshStoriesForFolderUpdate reacts to hinted story chats`() {
        val previous = emptyList<ChatModel>()
        val updated = listOf(chat(id = 3L, activeStoryStateType = "READ"))

        val result = shouldRefreshStoriesForFolderUpdate(
            previousChats = previous,
            updatedChats = updated,
            trackedStoryChatIds = emptySet()
        )

        assertTrue(result)
    }

    private fun chat(
        id: Long,
        order: Long = id,
        title: String = "chat $id",
        unreadCount: Int = 0,
        typingAction: String? = null,
        userStatus: String? = null,
        isArchived: Boolean = false,
        activeStoryStateType: String? = null
    ): ChatModel = ChatModel(
        id = id,
        title = title,
        order = order,
        unreadCount = unreadCount,
        typingAction = typingAction,
        userStatus = userStatus,
        isArchived = isArchived,
        activeStoryStateType = activeStoryStateType
    )
}
