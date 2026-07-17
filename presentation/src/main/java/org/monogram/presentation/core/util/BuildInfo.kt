package org.monogram.presentation.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val UNKNOWN_BUILD_INFO = "unknown"
private val utcTimeZone = TimeZone.getTimeZone("UTC")

fun Long.toBuildTimeString(locale: Locale = Locale.getDefault()): String {
    if (this <= 0L) return UNKNOWN_BUILD_INFO

    val formatter = SimpleDateFormat("dd.MM.yyyy HH:mm 'UTC'", locale)
    formatter.timeZone = utcTimeZone
    return formatter.format(Date(this))
}

fun String.toGitHubCommitTimeString(locale: Locale = Locale.getDefault()): String {
    if (isBlank()) return UNKNOWN_BUILD_INFO

    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = utcTimeZone
    }
    val formatter = SimpleDateFormat("dd.MM.yyyy HH:mm 'UTC'", locale).apply {
        timeZone = utcTimeZone
    }

    return runCatching {
        parser.parse(this)?.let(formatter::format)
    }.getOrNull() ?: this
}
