package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class LastSeenTest {
    private val zone = ZoneOffset.UTC

    @Test
    fun onlineUntilExpiryThenJustNow() {
        assertEquals(LastSeen.Online, LastSeen.resolve("online", 200, 100_000, zone))
        assertEquals(LastSeen.JustNow, LastSeen.resolve("online", 90, 100_000, zone))
    }

    @Test
    fun offlineUsesRelativeBuckets() {
        val now = 1_700_000_000_000L
        val nowSec = now / 1000
        assertEquals(LastSeen.JustNow, LastSeen.fromWasOnline(nowSec - 12, now, zone))
        assertEquals(LastSeen.MinutesAgo(5), LastSeen.fromWasOnline(nowSec - 5 * 60, now, zone))
        assertEquals(LastSeen.HoursAgo(2), LastSeen.fromWasOnline(nowSec - 2 * 3600, now, zone))
        val today = LastSeen.fromWasOnline(nowSec - 10 * 3600, now, zone)
        assertTrue(today is LastSeen.TodayAt)
        val yesterday = LastSeen.fromWasOnline(nowSec - 30 * 3600, now, zone)
        assertTrue(yesterday is LastSeen.YesterdayAt || yesterday is LastSeen.At)
    }

    @Test
    fun onlineHeartbeatsOnlyExtendTheLease() {
        assertTrue(LastSeen.affectsUi("online", 100L, "online", 200L))
        assertTrue(LastSeen.affectsUi("online", null, "online", 200L))
        assertTrue(LastSeen.affectsUi("online", 200L, "offline", 100L))
        assertFalse(LastSeen.affectsUi("online", 200L, "online", 200L))
        assertFalse(LastSeen.affectsUi("online", 200L, "online", 100L))
        assertFalse(LastSeen.affectsUi("offline", 100L, "offline", 100L))
        assertTrue(LastSeen.affectsUi("offline", 100L, "offline", 160L))
    }

    @Test
    fun privacyStatusesStayCoarse() {
        assertEquals(LastSeen.Recently, LastSeen.resolve("recently", null, 1, zone))
        assertEquals(LastSeen.LastWeek, LastSeen.resolve("last_week", null, 1, zone))
        assertEquals(LastSeen.LastMonth, LastSeen.resolve("last_month", null, 1, zone))
    }
}
