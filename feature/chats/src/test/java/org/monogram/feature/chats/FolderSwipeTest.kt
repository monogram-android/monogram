package org.monogram.feature.chats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.feature.chats.ui.FOLDER_EDGE_RESISTANCE
import org.monogram.feature.chats.ui.folderSettleOffset
import org.monogram.feature.chats.ui.folderSlide
import org.monogram.feature.chats.ui.folderSlidePages
import org.monogram.feature.chats.ui.folderSwipeOffset
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

    @Test
    fun slideKeepsCurrentPageUntilOffsetRevealsNeighbor() {
        val ids = listOf(null, 7, 3)
        val settled = folderSlide(null, null, ids, offset = 0f, width = 100f, rtl = false)
        assertEquals(null, settled.currentId)
        assertFalse(settled.hasIncoming)
        assertEquals(0f, settled.currentX)
        assertEquals(listOf(null), folderSlidePages(settled))

        val next = folderSlide(null, null, ids, offset = -40f, width = 100f, rtl = false)
        assertEquals(null, next.currentId)
        assertEquals(7, next.incomingId)
        assertEquals(-40f, next.currentX)
        assertEquals(60f, next.incomingX)

        val prev = folderSlide(3, 3, ids, offset = 40f, width = 100f, rtl = false)
        assertEquals(3, prev.currentId)
        assertEquals(7, prev.incomingId)
        assertEquals(40f, prev.currentX)
        assertEquals(-60f, prev.incomingX)
    }

    @Test
    fun chipChangePlacesIncomingPageOnTheTargetSide() {
        val ids = listOf(null, 7, 3)
        val forward = folderSlide(
            displayedId = null,
            selectedId = 7,
            folderIds = ids,
            offset = -25f,
            width = 100f,
            rtl = false,
        )
        assertEquals(7, forward.incomingId)
        assertEquals(75f, forward.incomingX)
        assertEquals(-100f, folderSettleOffset(null, 7, ids, 100f, rtl = false))
        assertEquals(100f, folderSettleOffset(7, null, ids, 100f, rtl = false))
        assertEquals(100f, folderSettleOffset(null, 7, ids, 100f, rtl = true))
    }

    @Test
    fun switchingToAllChatsKeepsTheIncomingPage() {
        val ids = listOf(null, 7, 3)
        val tap = folderSlide(
            displayedId = 7,
            selectedId = null,
            folderIds = ids,
            offset = 40f,
            width = 100f,
            rtl = false,
        )
        assertTrue(tap.hasIncoming)
        assertEquals(null, tap.incomingId)
        assertEquals(7, tap.currentId)
        assertEquals(listOf(null, 7), folderSlidePages(tap))

        val swipe = folderSlide(7, 7, ids, offset = 40f, width = 100f, rtl = false)
        assertTrue(swipe.hasIncoming)
        assertEquals(null, swipe.incomingId)
        assertEquals(listOf(null, 7), folderSlidePages(swipe))
    }

    @Test
    fun edgeFoldersRubberBandInsteadOfRevealingAMissingPage() {
        assertEquals(50f * FOLDER_EDGE_RESISTANCE, folderSwipeOffset(50f, 100f, 0, 3, false))
        assertEquals(-50f * FOLDER_EDGE_RESISTANCE, folderSwipeOffset(-50f, 100f, 2, 3, false))
        assertEquals(-50f, folderSwipeOffset(-50f, 100f, 0, 3, false))
        assertEquals(50f * FOLDER_EDGE_RESISTANCE, folderSwipeOffset(50f, 100f, 2, 3, true))
    }
}
