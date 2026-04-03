package org.monogram.presentation.core.util

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Test cases for date utils
 **/
class DateUtilsTest {
    private val ruLocale = Locale.forLanguageTag("ru")
    private val dateFormatter = SimpleDateFormat("dd.MM.yyyy HH:mm", ruLocale)
    private val mockToday: Date = dateFormatter.parse("20.03.2024 12:00")!!

    @Test
    fun `When date are today, then returns only hours and minutes`() {
        val targetDate = dateFormatter.parse("20.03.2024 15:20")!!
        val result = targetDate.toShortRelativeDate(locale = ruLocale, now = mockToday)

        assertEquals("15:20", result)
    }

    @Test
    fun `When date are yesterday and within 1 week, then returns day of week`() {
        // Yesterday
        val yesterday = dateFormatter.parse("19.03.2024 10:15")!!
        assertEquals("вт", yesterday.toShortRelativeDate(locale = ruLocale, now = mockToday))

        // Six days ago
        val sixDaysAgo = dateFormatter.parse("14.03.2024 09:00")!!
        assertEquals("чт", sixDaysAgo.toShortRelativeDate(locale = ruLocale, now = mockToday))
    }

    @Test
    fun `When date are older than 1 week but within 1 year, then return day and month`() {
        // 13.03.2024 - ровно 7 дней назад
        val sevenDaysAgo = dateFormatter.parse("13.03.2024 14:00")!!
        assertEquals("13 мар", sevenDaysAgo.toShortRelativeDate(locale = ruLocale, now = mockToday))

        // Прошлый месяц (меньше 365 дней назад)
        val monthsAgo = dateFormatter.parse("25.10.2023 18:30")!!
        assertEquals("25 окт", monthsAgo.toShortRelativeDate(locale = ruLocale, now = mockToday))
    }

    @Test
    fun `When date are older than 1 year, then return full date`() {
        // Past year
        val olderThanYear = dateFormatter.parse("10.03.2023 11:11")!!
        assertEquals("10.03.2023", olderThanYear.toShortRelativeDate(locale = ruLocale, now = mockToday))

        // A long time ago...
        val veryOldDate = dateFormatter.parse("01.01.2020 00:00")!!
        assertEquals("01.01.2020", veryOldDate.toShortRelativeDate(locale = ruLocale, now = mockToday))
    }

    @Test
    fun `Then future date is older than 1 year, then return full date`() {
        val futureDate = dateFormatter.parse("16.03.2029 10:00")!!
        assertEquals("16.03.2029", futureDate.toShortRelativeDate(locale = ruLocale, now = mockToday))
    }
}