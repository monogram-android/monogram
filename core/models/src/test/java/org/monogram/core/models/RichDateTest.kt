package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

class RichDateTest {
    @Test
    fun serverDateReplacesFallbackString() {
        val (text, entities) = replaceRichDates(
            "On fallback",
            listOf(TextEntity("date", 3, 8, "1710000000|8")),
            ZoneOffset.UTC,
            Locale.US,
        )
        assertEquals("On 09.03.24", text)
        assertEquals("date", entities.single().kind)
        assertEquals(3, entities.single().offset)
        assertEquals("09.03.24".length, entities.single().length)
        assertEquals("1710000000|8", entities.single().url)
    }

    @Test
    fun relativeFlagIsNotAnAbsoluteTimestamp() {
        val now = Instant.parse("2026-10-07T00:00:00Z")
        assertEquals(
            "1 month ago",
            formatRichDate("1787067000|1", ZoneOffset.UTC, Locale.US, now),
        )
        assertEquals(
            "00:00:00",
            formatRichDate("1735689600|4", ZoneOffset.UTC, Locale.US, now),
        )
        assertEquals(
            "Wednesday, 1 January 2025 at 00:00:00",
            formatRichDate("1735689600|52", ZoneOffset.UTC, Locale.US, now),
        )
    }
}
