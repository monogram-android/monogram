package org.monogram.presentation.features.chats.list

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.FolderModel
import org.monogram.presentation.core.ui.ScreenSwipeBackAction
import org.monogram.presentation.core.ui.ScreenSwipeBackPreview

class ChatListSwipeBackStateTest {
    @Test
    fun `archived folder exposes local archive return swipe to remembered folder`() {
        val swipeState = resolveChatListSwipeBackState(
            state = ChatListComponent.State(
                folders = listOf(
                    FolderModel(id = -1, title = "All chats"),
                    FolderModel(id = 3, title = "Work"),
                    FolderModel(id = 7, title = "Family"),
                ),
                selectedFolderId = -2,
                lastNonArchiveFolderId = 7,
            ),
            showAllChatsFolder = true,
        )

        assertTrue(swipeState.isSupported)
        assertFalse(swipeState.isBlocked)
        assertEquals(ScreenSwipeBackAction.LocalChatListArchiveReturn, swipeState.action)
        assertEquals(ScreenSwipeBackPreview.ChatListFolder, swipeState.preview)
    }

    @Test
    fun `normal folder does not expose archive return swipe`() {
        val swipeState = resolveChatListSwipeBackState(
            state = ChatListComponent.State(
                folders = listOf(
                    FolderModel(id = -1, title = "All chats"),
                    FolderModel(id = 3, title = "Work"),
                ),
                selectedFolderId = 3,
            ),
            showAllChatsFolder = true,
        )

        assertFalse(swipeState.isSupported)
    }

    @Test
    fun `archive return resolves to all chats when it was last selected`() {
        val targetFolderId = resolveArchiveReturnFolderId(
            folders = listOf(
                FolderModel(id = -1, title = "All chats"),
                FolderModel(id = 3, title = "Work"),
            ),
            showAllChatsFolder = true,
            lastNonArchiveFolderId = -1,
        )

        assertEquals(-1, targetFolderId)
    }

    @Test
    fun `archive return falls back when remembered folder is hidden`() {
        val targetFolderId = resolveArchiveReturnFolderId(
            folders = listOf(
                FolderModel(id = -1, title = "All chats"),
                FolderModel(id = 3, title = "Work"),
                FolderModel(id = 7, title = "Family"),
            ),
            showAllChatsFolder = false,
            lastNonArchiveFolderId = -1,
        )

        assertEquals(3, targetFolderId)
    }

    @Test
    fun `archive folder selection does not overwrite remembered non archive folder`() {
        val state = ChatListComponent.State(
            folders = listOf(
                FolderModel(id = -1, title = "All chats"),
                FolderModel(id = 3, title = "Work"),
            ),
            selectedFolderId = -2,
            lastNonArchiveFolderId = 3,
        )

        val targetFolderId = resolveArchiveReturnFolderId(
            folders = state.folders,
            showAllChatsFolder = true,
            lastNonArchiveFolderId = state.lastNonArchiveFolderId,
        )

        assertEquals(3, targetFolderId)
    }

    @Test
    fun `custom back state blocks archive swipe`() {
        val swipeState = resolveChatListSwipeBackState(
            state = ChatListComponent.State(
                folders = listOf(
                    FolderModel(id = -1, title = "All chats"),
                    FolderModel(id = 3, title = "Work"),
                ),
                selectedFolderId = -2,
                isSearchActive = true,
            ),
            showAllChatsFolder = true,
        )

        assertTrue(swipeState.isSupported)
        assertTrue(swipeState.isBlocked)
    }

    @Test
    fun `transient overlay blocks archive swipe`() {
        val swipeState = resolveChatListSwipeBackState(
            state = ChatListComponent.State(
                folders = listOf(
                    FolderModel(id = -1, title = "All chats"),
                    FolderModel(id = 3, title = "Work"),
                ),
                selectedFolderId = -2,
            ),
            showAllChatsFolder = true,
            hasTransientBlockingUi = true,
        )

        assertTrue(swipeState.isBlocked)
    }
}
