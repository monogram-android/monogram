package org.monogram.feature.chats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.ARCHIVE_FOLDER_ID
import org.monogram.core.models.contains

class FolderScrollTest {
    @Test
    fun switchFolderSavesCurrentAndSelectsNext() {
        val saved = mapOf(folderScrollKey(null) to FolderListScroll(4, 12))
        val next = onFolderChipClick(
            selectedId = null,
            tappedId = 9,
            currentScroll = FolderListScroll(7, 3),
            saved = saved,
        )
        assertEquals(9, next.selectedId)
        assertFalse(next.scrollToTop)
        assertEquals(FolderListScroll(7, 3), next.saved[folderScrollKey(null)])
    }

    @Test
    fun retapSelectedFolderScrollsToTop() {
        val next = onFolderChipClick(
            selectedId = 2,
            tappedId = 2,
            currentScroll = FolderListScroll(11, 40),
            saved = emptyMap(),
        )
        assertEquals(2, next.selectedId)
        assertTrue(next.scrollToTop)
        assertEquals(FolderListScroll(), next.saved[folderScrollKey(2)])
    }

    @Test
    fun restoreAfterSwitchUsesSavedOffset() {
        var saved = emptyMap<Int, FolderListScroll>()
        val leaveAll = onFolderChipClick(
            selectedId = null,
            tappedId = 4,
            currentScroll = FolderListScroll(15, 8),
            saved = saved,
        )
        saved = leaveAll.saved
        val back = onFolderChipClick(
            selectedId = 4,
            tappedId = null,
            currentScroll = FolderListScroll(2, 0),
            saved = saved,
        )
        assertEquals(null, back.selectedId)
        assertEquals(FolderListScroll(15, 8), back.saved[folderScrollKey(null)])
    }

    @Test
    fun archiveRowSavesAllTabScroll() {
        val next = onFolderChipClick(
            selectedId = null,
            tappedId = ARCHIVE_FOLDER_ID,
            currentScroll = FolderListScroll(22, 5),
            saved = emptyMap(),
        )
        assertEquals(ARCHIVE_FOLDER_ID, next.selectedId)
        assertEquals(FolderListScroll(22, 5), next.saved[folderScrollKey(null)])
    }

    @Test
    fun clampKeepsValidSavedPosition() {
        val saved = FolderListScroll(index = 12, offset = 40)
        assertEquals(saved, clampFolderScroll(saved, size = 40))
    }

    @Test
    fun clampPullsStaleIndexAndOffsetBackIntoRange() {
        val stale = FolderListScroll(index = 90, offset = 120)
        val clamped = clampFolderScroll(stale, size = 25)
        assertEquals(24, clamped.index)
        assertEquals(0, clamped.offset)
        val empty = clampFolderScroll(stale, size = 0)
        assertEquals(0, empty.index)
        assertEquals(0, empty.offset)
    }
}
