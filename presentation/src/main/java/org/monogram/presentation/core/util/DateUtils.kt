package org.monogram.presentation.core.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Formats date as relative string as for day, week, year, periods and so-on
 *
 * @param locale a [Locale] object which used with this date
 * @param now optional current date (for custom formatting or testing)
 **/
fun Date.toShortRelativeDate(
    locale: Locale = Locale.getDefault(),
    now: Date = Date()
): String {
    val currentCalendar = Calendar.getInstance(locale).apply { time = now }
    val targetCalendar = Calendar.getInstance(locale).apply { time = this@toShortRelativeDate }

    val currentDayStart = currentCalendar.clone() as Calendar
    currentDayStart.apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    val targetDayStart = targetCalendar.clone() as Calendar
    targetDayStart.apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    val diffMillis = abs(currentDayStart.timeInMillis - targetDayStart.timeInMillis)

    val diffDays = (diffMillis.toDouble() / (1000 * 60 * 60 * 24)).roundToLong()

    return when (diffDays) {
        0L -> {
            SimpleDateFormat("HH:mm", locale).format(this)
        }
        in 1..6 -> {
            SimpleDateFormat("EEE", locale).format(this)
                .lowercase(locale)
                .replace(".", "")
        }
        in 7..365 -> {
            SimpleDateFormat("d MMM", locale).format(this)
                .lowercase(locale)
                .replace(".", "")
        }
        else -> {
            SimpleDateFormat("dd.MM.yyyy", locale).format(this)
        }
    }
}
