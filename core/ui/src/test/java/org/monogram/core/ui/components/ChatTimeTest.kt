package org.monogram.core.ui.components

import java.util.Calendar
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ChatTimeTest {
    private lateinit var locale: Locale

    @Before
    fun setUp() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun tearDown() {
        Locale.setDefault(locale)
    }

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, minute)
        }.timeInMillis

    @Test
    fun todayShowsTheClock() {
        val now = millis(2024, Calendar.SEPTEMBER, 12, 10, 0)
        assertEquals(
            "07:57",
            formatChatTime(
                millis(2024, Calendar.SEPTEMBER, 12, 7, 57),
                nowMillis = now,
            ),
        )
    }

    @Test
    fun secondsAreAccepted() {
        val now = millis(2024, Calendar.SEPTEMBER, 12, 10, 0)
        assertEquals(
            "09:05",
            formatChatTime(
                millis(2024, Calendar.SEPTEMBER, 12, 9, 5) / 1000,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun yesterdayUsesTheLocalizedLabel() {
        val now = millis(2024, Calendar.SEPTEMBER, 12, 10, 0)
        assertEquals(
            "Вчера",
            formatChatTime(
                millis(2024, Calendar.SEPTEMBER, 11, 23, 59),
                nowMillis = now,
                yesterdayLabel = "Вчера",
            ),
        )
    }

    @Test
    fun olderMessagesShowDayAndMonth() {
        val now = millis(2024, Calendar.SEPTEMBER, 12, 10, 0)
        assertEquals(
            "5 Sep",
            formatChatTime(millis(2024, Calendar.SEPTEMBER, 5, 8, 0), nowMillis = now),
        )
    }

    @Test
    fun emptyDatesRenderNothing() {
        assertEquals("", formatChatTime(null))
        assertEquals("", formatChatTime(0L))
    }
}
