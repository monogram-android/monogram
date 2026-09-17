package org.monogram.core.ui.components

import java.util.Calendar
import java.util.Locale

/**
 * Formats a unix timestamp (seconds or millis) for a chat-list row: `07:57` today,
 * `Yesterday`, or `11 Sep`. [nowMillis] is injectable so the result is testable.
 */
fun formatChatTime(
    epoch: Long?,
    nowMillis: Long = System.currentTimeMillis(),
    yesterdayLabel: String = "Yesterday",
): String {
    if (epoch == null || epoch <= 0L) return ""
    val millis = if (epoch < 10_000_000_000L) epoch * 1000L else epoch
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
    return when {
        isSameDay(then, now) ->
            String.format(Locale.getDefault(), "%02d:%02d", then.get(Calendar.HOUR_OF_DAY), then.get(Calendar.MINUTE))

        isPreviousDay(then, now) -> yesterdayLabel

        else -> String.format(
            Locale.getDefault(),
            "%d %s",
            then.get(Calendar.DAY_OF_MONTH),
            then.getDisplayName(Calendar.MONTH, Calendar.SHORT, Locale.getDefault()).orEmpty(),
        )
    }
}

private fun isSameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

private fun isPreviousDay(then: Calendar, now: Calendar): Boolean {
    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    return isSameDay(then, yesterday)
}
