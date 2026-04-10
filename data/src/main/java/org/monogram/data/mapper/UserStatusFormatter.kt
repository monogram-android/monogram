package org.monogram.data.mapper

import android.text.format.DateUtils
import org.drinkless.tdlib.TdApi
import org.monogram.core.date.DateFormatManager
import org.monogram.domain.repository.StringProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun formatChatUserStatus(
    status: TdApi.UserStatus,
    stringProvider: StringProvider,
    dateFormatManager: DateFormatManager,
    isBot: Boolean = false
): String {
    if (isBot) return stringProvider.getString("chat_mapper_bot")
    return when (status) {
        is TdApi.UserStatusOnline -> stringProvider.getString("chat_mapper_online")
        is TdApi.UserStatusOffline -> {
            val wasOnline = status.wasOnline.toLong() * 1000L
            if (wasOnline == 0L) return stringProvider.getString("chat_mapper_offline")
            val now = System.currentTimeMillis()
            val diff = now - wasOnline
            when {
                diff < 60 * 1000 -> stringProvider.getString("chat_mapper_seen_just_now")
                diff < 60 * 60 * 1000 -> {
                    val minutes = diff / (60 * 1000L)
                    if (minutes == 1L) stringProvider.getString("chat_mapper_seen_minutes_ago", 1)
                    else stringProvider.getString("chat_mapper_seen_minutes_ago_plural", minutes)
                }

                DateUtils.isToday(wasOnline) -> {
                    val date = Date(wasOnline)
                    val format = SimpleDateFormat(
                        dateFormatManager.getHourMinuteFormat(),
                        Locale.getDefault()
                    )
                    stringProvider.getString("chat_mapper_seen_at", format.format(date))
                }

                isYesterday(wasOnline) -> {
                    val date = Date(wasOnline)
                    val format = SimpleDateFormat(
                        dateFormatManager.getHourMinuteFormat(),
                        Locale.getDefault()
                    )
                    stringProvider.getString("chat_mapper_seen_yesterday", format.format(date))
                }

                else -> {
                    val date = Date(wasOnline)
                    val format = SimpleDateFormat("dd.MM.yy", Locale.getDefault())
                    stringProvider.getString("chat_mapper_seen_date", format.format(date))
                }
            }
        }

        is TdApi.UserStatusRecently -> stringProvider.getString("chat_mapper_seen_recently")
        is TdApi.UserStatusLastWeek -> stringProvider.getString("chat_mapper_seen_week")
        is TdApi.UserStatusLastMonth -> stringProvider.getString("chat_mapper_seen_month")
        is TdApi.UserStatusEmpty -> stringProvider.getString("chat_mapper_offline")
        else -> ""
    }
}

private fun isYesterday(timestamp: Long): Boolean {
    return DateUtils.isToday(timestamp + DateUtils.DAY_IN_MILLIS)
}