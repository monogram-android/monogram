package org.monogram.feature.dialog.ui

import org.junit.Assert.*
import org.junit.Test
import org.monogram.feature.dialog.store.validSendSchedule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class SendScheduleTest {
    private fun day(value: String) =
        LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun pickerDatesRepresentLocalCalendarDaysRatherThanUtcTimes() {
        assertEquals(
            Instant.parse("2026-03-01T04:30:00Z").epochSecond.toInt(),
            scheduledEpoch(day("2026-03-01"), 13, 30, ZoneId.of("Asia/Tokyo"))
        )
    }

    @Test
    fun nonexistentDstTimeIsRejectedAndOverlapUsesTheEarlierOffset() {
        val zone = ZoneId.of("Europe/Berlin")
        assertNull(scheduledEpoch(day("2026-03-29"), 2, 30, zone))
        assertEquals(
            Instant.parse("2026-10-25T00:30:00Z").epochSecond.toInt(),
            scheduledEpoch(day("2026-10-25"), 2, 30, zone)
        )
    }

    @Test
    fun scheduleBoundsAndMalformedPickerFieldsCannotBecomeImmediateSends() {
        assertTrue(validSendSchedule(null, 1_000))
        assertFalse(validSendSchedule(1_059, 1_000))
        assertTrue(validSendSchedule(1_060, 1_000))
        assertFalse(validSendSchedule((1_000 + 365L * 86400 + 1).toInt(), 1_000))
        assertFalse(validSendSchedule(Int.MAX_VALUE, 1_000))
        assertNull(scheduledEpoch(null, 12, 0, ZoneOffset.UTC))
        assertNull(scheduledEpoch(day("2026-03-01"), 24, 0, ZoneOffset.UTC))
    }
}
