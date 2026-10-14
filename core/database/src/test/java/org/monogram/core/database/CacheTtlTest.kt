package org.monogram.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.database.entity.MessageHoleEntity

class CacheTtlTest {
    private val now = 1_700_000_000L
    private val old = now - CACHE_MESSAGE_TTL_SECONDS - 60
    private val recent = now - 86_400

    @Test
    fun keepsRecentMessagesAndTheNewestPage() {
        val oldTail = (1..120).map { CachedMessage(it, old) }
        val recentRow = CachedMessage(121, recent)
        val drop = expiredMessageIds(oldTail + recentRow, now)
        assertEquals((1..21).toList(), drop)
        assertFalse(121 in drop)
        assertFalse(22 in drop)
        assertTrue(expiredMessageIds((1..150).map { CachedMessage(it, recent) }, now).isEmpty())
    }

    @Test
    fun pendingFailedOpenChatAndFreshProfilesStay() {
        val rows = listOf(
            CachedMessage(1, old, pending = true),
            CachedMessage(2, old, failed = true),
            CachedMessage(3, old),
            CachedMessage(4, old),
        )
        assertEquals(listOf(3), expiredMessageIds(rows, now, keepNewest = 1))
        assertEquals(listOf(8L), chatsToPrune(listOf(4L, 8L), openChatId = 4L))
        assertFalse(
            profileCacheStale(
                updatedAtMillis = 50_000,
                nowMillis = 60_000,
                ttlMillis = 30_000
            )
        )
        assertTrue(profileCacheStale(updatedAtMillis = 1, nowMillis = 50_000, ttlMillis = 30_000))
    }

    @Test
    fun deletedTailIsAHoleAndTheKeptPageIsNot() {
        val existing = listOf(MessageHoleEntity(7L, 1, Int.MAX_VALUE))
        val kept = (100..110).toList()
        val holes = holesAfterCacheTtl(7L, existing, deletedIds = (1..5).toList(), keptIds = kept)
        assertTrue(holes.any { 1 in it.startId..it.endId && 5 in it.startId..it.endId })
        assertTrue(holes.none { hole -> kept.any { it in hole.startId..hole.endId } })
    }
}
