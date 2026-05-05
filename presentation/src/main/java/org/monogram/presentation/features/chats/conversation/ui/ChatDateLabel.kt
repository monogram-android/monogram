package org.monogram.presentation.features.chats.conversation.ui

import android.content.Context
import org.monogram.presentation.R
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

fun formatChatDayLabel(timestamp: Int, context: Context, locale: Locale = Locale.getDefault()): String {
    val date = Date(timestamp.toLong() * 1000)
    val now = Calendar.getInstance(locale)
    val msgCal = Calendar.getInstance(locale).apply { time = date }

    val nowYear = now.get(Calendar.YEAR)
    val nowDay = now.get(Calendar.DAY_OF_YEAR)
    val msgYear = msgCal.get(Calendar.YEAR)
    val msgDay = msgCal.get(Calendar.DAY_OF_YEAR)

    if (nowYear == msgYear) {
        val dayDiff = nowDay - msgDay
        return when {
            dayDiff == 0 -> context.getString(R.string.chat_date_today)
            dayDiff == 1 -> context.getString(R.string.chat_date_yesterday)
            dayDiff in 2..6 -> SimpleDateFormat("EEEE", locale).format(date)
            else -> SimpleDateFormat("d MMMM", locale).format(date)
        }
    }

    if (nowYear - msgYear == 1 && msgDay >= 359 && nowDay <= 6) {
        val daysInMsgYear = msgCal.getActualMaximum(Calendar.DAY_OF_YEAR)
        val dayDiff = (daysInMsgYear - msgDay) + nowDay
        return when {
            dayDiff == 0 -> context.getString(R.string.chat_date_today)
            dayDiff == 1 -> context.getString(R.string.chat_date_yesterday)
            dayDiff in 2..6 -> SimpleDateFormat("EEEE", locale).format(date)
            else -> SimpleDateFormat("d MMMM yyyy", locale).format(date)
        }
    }

    return SimpleDateFormat("d MMMM yyyy", locale).format(date)
}
