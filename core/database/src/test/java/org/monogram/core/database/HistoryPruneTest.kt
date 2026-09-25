package org.monogram.core.database

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryPruneTest {
    @Test
    fun dropsCachedIdsNewerThanDialogLastMessage() {
        assertEquals(listOf(12, 13), staleHistoryIds(listOf(10, 11, 12, 13), lastMessageId = 11))
    }

    @Test
    fun keepsCacheWhenLastIdUnknown() {
        assertEquals(emptyList<Int>(), staleHistoryIds(listOf(10, 11), lastMessageId = 0))
    }

    @Test
    fun noDropWhenCacheIsOnLastMessage() {
        assertEquals(emptyList<Int>(), staleHistoryIds(listOf(8, 9, 10), lastMessageId = 10))
    }

    @Test
    fun historyCacheWindowsStayWithinTelegramLimit() {
        assertEquals(100, HISTORY_CACHE_WINDOW)
    }

    @Test
    fun pendingWithRandomIdSurvivesProcessDeathCleanup() {
        assertEquals(false, shouldDropUnsentAfterRestart(pending = true, id = -3, randomId = 99L))
        assertEquals(true, shouldDropUnsentAfterRestart(pending = true, id = -3, randomId = null))
        assertEquals(true, shouldDropUnsentAfterRestart(pending = true, id = -3, randomId = 0L))
    }

    @Test
    fun pruneMissingLatestKeepsLiveRowsNewerThanTheFetchedPage() {
        assertEquals(
            listOf(12),
            staleIdsInsideFetchedWindow(
                cachedIds = listOf(10, 11, 12, 13, 20),
                fetchedIds = listOf(10, 11, 13),
            ),
        )
    }
}
