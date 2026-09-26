package org.monogram.feature.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import java.time.ZoneId
import java.util.Locale

class DialogTimeTest {
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val newYork = ZoneId.of("America/New_York")
    private val utc = ZoneId.of("UTC")
    private val locale = Locale.US

    @Test
    fun sameUtcInstantCanBeDifferentLocalDays() {
        val epoch = 1_704_067_200L // 2023-12-31 16:00 UTC
        assertEquals(
            java.time.LocalDate.of(2024, 1, 1),
            DialogTime.localDate(epoch, tokyo),
        )
        assertEquals(
            java.time.LocalDate.of(2023, 12, 31),
            DialogTime.localDate(epoch, newYork),
        )
        assertFalse(DialogTime.sameLocalDay(epoch, epoch + 16 * 3600, tokyo))
        assertTrue(DialogTime.sameLocalDay(epoch, epoch + 2 * 3600, newYork))
    }

    @Test
    fun todayYesterdayAndOlderUseLocalCalendar() {
        val now = 1_720_000_000L // 2024-07-03 06:26:40 UTC
        val todayNy = DialogTime.dayLabel(now, now, newYork, locale)
        val yesterdayNy = DialogTime.dayLabel(now - 86_400, now, newYork, locale)
        val lastWeek = DialogTime.dayLabel(now - 3 * 86_400, now, newYork, locale)
        val lastYear = DialogTime.dayLabel(1_672_531_200L, now, utc, locale)
        assertEquals(DialogDayKind.Today, todayNy.kind)
        assertEquals(DialogDayKind.Yesterday, yesterdayNy.kind)
        assertEquals(DialogDayKind.Weekday, lastWeek.kind)
        assertEquals(DialogDayKind.OtherYear, lastYear.kind)
        assertTrue(lastYear.text.contains("2023"))
    }

    @Test
    fun formatTimeUsesZone() {
        val epoch = 1_704_067_200L
        val tokyoTime = DialogTime.formatTime(epoch, tokyo, locale)
        val nyTime = DialogTime.formatTime(epoch, newYork, locale)
        assertTrue(tokyoTime != nyTime)
    }

    @Test
    fun formatTimeHonors24HourClock() {
        val epoch = 1_704_124_800L // 2024-01-01 16:00 UTC
        val twentyFour = DialogTime.formatTime(epoch, utc, locale, use24Hour = true)
        val twelve = DialogTime.formatTime(epoch, utc, locale, use24Hour = false)
        assertEquals("16:00", twentyFour)
        assertTrue(twelve.uppercase().contains("PM"))
        assertFalse(twelve.contains("16"))
    }

    @Test
    fun retryTextTakesFirstFailedOutgoing() {
        val chat = PeerId(1)
        val failed = Message(
            id = MessageId(chat, 2),
            senderId = null,
            text = "  hello  ",
            date = 1,
            outgoing = true,
            failed = true,
        )
        val pending = Message(
            id = MessageId(chat, 3),
            senderId = null,
            text = "later",
            date = 2,
            outgoing = true,
            pending = true,
        )
        assertEquals("hello", nextRetryText(listOf(pending, failed)))
        assertEquals(null, nextRetryText(listOf(pending)))
    }
}
