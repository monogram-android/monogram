package org.monogram.presentation.features.chats.currentChat.components.channels

import java.text.SimpleDateFormat
import java.util.*

fun formatTime(ts: Int): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts.toLong() * 1000))

fun formatViews(views: Int): String {
    return when {
        views < 1000 -> views.toString()
        views < 1000000 -> String.format(Locale.US, "%.1fK", views / 1000.0)
        else -> String.format(Locale.US, "%.1fM", views / 1000000.0)
    }
}

fun formatCommentsCount(count: Int): String {
    return when {
        count <= 0 -> "Leave a comment"
        count < 1000 -> "$count comments"
        count < 1000000 -> String.format(Locale.US, "%.1fK comments", count / 1000.0)
        else -> String.format(Locale.US, "%.1fM comments", count / 1000000.0)
    }
}

fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", m, s)
}
