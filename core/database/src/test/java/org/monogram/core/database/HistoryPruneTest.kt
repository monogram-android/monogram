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

}
