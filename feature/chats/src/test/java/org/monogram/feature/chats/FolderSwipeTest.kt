package org.monogram.feature.chats

import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.feature.chats.ui.swipedFolderIndex

class FolderSwipeTest {
    @Test
    fun swipesFollowTheVisibleOrderOfAllChatsAndUserFolders() {
        val visibleIds = listOf(null, 7, 3)
        assertEquals(7, visibleIds[swipedFolderIndex(0, 3, -80f, 64f, false)])
        assertEquals(3, visibleIds[swipedFolderIndex(1, 3, -80f, 64f, false)])
        assertEquals(null, visibleIds[swipedFolderIndex(1, 3, 80f, 64f, false)])
    }

    @Test
    fun rtlReversesPhysicalDirectionAndEdgesNeverWrap() {
        assertEquals(2, swipedFolderIndex(1, 3, 80f, 64f, true))
        assertEquals(0, swipedFolderIndex(1, 3, -80f, 64f, true))
        assertEquals(0, swipedFolderIndex(0, 3, 80f, 64f, false))
        assertEquals(2, swipedFolderIndex(2, 3, -80f, 64f, false))
    }

    @Test
    fun cancelledShortDragsAndMissingSelectionKeepCurrentFolder() {
        assertEquals(1, swipedFolderIndex(1, 3, -63f, 64f, false))
        assertEquals(1, swipedFolderIndex(1, 3, 0f, 64f, false))
        assertEquals(-1, swipedFolderIndex(-1, 3, -80f, 64f, false))
    }
}
