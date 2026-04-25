package org.monogram.presentation.features.chats.conversation.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun isTodayTimestamp(timestamp: Int, locale: Locale = Locale.getDefault()): Boolean {
    val calendar = Calendar.getInstance(locale)
    val todayYear = calendar.get(Calendar.YEAR)
    val todayDayOfYear = calendar.get(Calendar.DAY_OF_YEAR)

    calendar.time = Date(timestamp.toLong() * 1000)
    return calendar.get(Calendar.YEAR) == todayYear &&
            calendar.get(Calendar.DAY_OF_YEAR) == todayDayOfYear
}

fun formatChatDayLabel(timestamp: Int, locale: Locale = Locale.getDefault()): String {
    val date = Date(timestamp.toLong() * 1000)
    val calendar = Calendar.getInstance(locale)
    val currentYear = calendar.get(Calendar.YEAR)
    calendar.time = date
    val messageYear = calendar.get(Calendar.YEAR)

    val pattern = if (messageYear == currentYear) "d MMMM" else "d MMMM yyyy"
    return SimpleDateFormat(pattern, locale).format(date)
}
