package org.monogram.presentation.features.chats.currentChat.components.channels

import android.content.Context
import org.monogram.presentation.R
import java.text.SimpleDateFormat
import java.util.*

fun formatTime(context: Context, ts: Int): String =
    SimpleDateFormat(context.getString(R.string.format_time), Locale.getDefault()).format(Date(ts.toLong() * 1000))

fun formatViews(context: Context, views: Int): String {
    return when {
        views < 1000 -> views.toString()
        views < 1000000 -> context.getString(R.string.views_k, views / 1000.0)
        else -> context.getString(R.string.views_m, views / 1000000.0)
    }
}

fun formatCommentsCount(context: Context, count: Int): String {
    return when {
        count <= 0 -> context.getString(R.string.comment_leave)
        count < 1000 -> context.getString(R.string.comment_count, count)
        count < 1000000 -> context.getString(R.string.comment_count_k, count / 1000.0)
        else -> context.getString(R.string.comment_count_m, count / 1000000.0)
    }
}

fun formatDuration(context: Context, seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return context.getString(R.string.format_duration, m, s)
}
