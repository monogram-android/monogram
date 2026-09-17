package org.monogram.core.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderChipRowTest {
    private fun all(andMore: List<FolderChipItem> = emptyList()) =
        FolderChips(listOf(FolderChipItem(id = null, label = "All chats", isAll = true)) + andMore)

    @Test
    fun equalContentFromFreshInstancesComparesEqual() {
        assertEquals(
            all(listOf(FolderChipItem(id = 7, label = "Work", unread = 2))),
            all(listOf(FolderChipItem(id = 7, label = "Work", unread = 2))),
        )
    }

    @Test
    fun unreadAndMutedCountersArePartOfTheValue() {
        assertNotEquals(
            all(listOf(FolderChipItem(id = 7, label = "Work", unread = 1))),
            all(listOf(FolderChipItem(id = 7, label = "Work", unread = 2))),
        )
        assertNotEquals(
            all(listOf(FolderChipItem(id = 7, label = "Work", mutedUnread = 0))),
            all(listOf(FolderChipItem(id = 7, label = "Work", mutedUnread = 3))),
        )
    }

    @Test
    fun selectionFlagsAndLabelsArePartOfTheValue() {
        assertNotEquals(
            all(listOf(FolderChipItem(id = 7, label = "Work"))),
            all(listOf(FolderChipItem(id = 7, label = "Work", isUnreadFilter = true))),
        )
        assertNotEquals(
            all(listOf(FolderChipItem(id = 7, label = "Work"))),
            all(listOf(FolderChipItem(id = 7, label = "Job"))),
        )
    }

    @Test
    fun orderIsPartOfTheValue() {
        val work = FolderChipItem(id = 7, label = "Work")
        val fun_ = FolderChipItem(id = 8, label = "Fun")
        assertNotEquals(all(listOf(work, fun_)), all(listOf(fun_, work)))
    }

    @Test
    fun longPressIsDroppedOnlyPastTheTouchSlop() {
        // isScrollPastSlop compares squared distances; slop here is an 8px touch slop.
        val slop = 8f * 8f
        assertFalse(isScrollPastSlop(dx = 0f, dy = 0f, touchSlopSquared = slop))
        assertFalse(isScrollPastSlop(dx = 8f, dy = 0f, touchSlopSquared = slop))
        assertTrue(isScrollPastSlop(dx = 8.5f, dy = 0f, touchSlopSquared = slop))
        assertFalse(isScrollPastSlop(dx = 5f, dy = 5f, touchSlopSquared = slop))
        assertTrue(isScrollPastSlop(dx = 6f, dy = 6f, touchSlopSquared = slop))
        assertTrue(isScrollPastSlop(dx = -9f, dy = 0f, touchSlopSquared = slop))
        assertTrue(isScrollPastSlop(dx = 0f, dy = -9f, touchSlopSquared = slop))
    }
}
