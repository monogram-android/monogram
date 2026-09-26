package org.monogram.feature.dialog

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class DialogDayKind {
    Today,
    Yesterday,
    Weekday,
    ThisYear,
    OtherYear,
}

data class DialogDayLabel(
    val kind: DialogDayKind,
    val text: String,
)

object DialogTime {
    fun localDate(epochSeconds: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochSecond(epochSeconds.coerceAtLeast(0)).atZone(zone).toLocalDate()

    fun sameLocalDay(a: Long, b: Long, zone: ZoneId): Boolean =
        localDate(a, zone) == localDate(b, zone)

    fun dayLabel(
        epochSeconds: Long,
        nowSeconds: Long,
        zone: ZoneId,
        locale: Locale,
    ): DialogDayLabel {
        val day = localDate(epochSeconds, zone)
        val today = localDate(nowSeconds, zone)
        val kind = when {
            day == today -> DialogDayKind.Today
            day == today.minusDays(1) -> DialogDayKind.Yesterday
            day.isAfter(today.minusDays(7)) -> DialogDayKind.Weekday
            day.year == today.year -> DialogDayKind.ThisYear
            else -> DialogDayKind.OtherYear
        }
        val text = when (kind) {
            DialogDayKind.Today, DialogDayKind.Yesterday -> ""
            DialogDayKind.Weekday ->
                day.format(DateTimeFormatter.ofPattern("EEEE", locale))
            DialogDayKind.ThisYear ->
                day.format(DateTimeFormatter.ofPattern("MMM d", locale))
            DialogDayKind.OtherYear ->
                day.format(DateTimeFormatter.ofPattern("MMM d, yyyy", locale))
        }
        return DialogDayLabel(kind, text)
    }

    fun formatTime(
        epochSeconds: Long,
        zone: ZoneId,
        locale: Locale,
        use24Hour: Boolean = true,
    ): String {
        val pattern = if (use24Hour) "HH:mm" else "h:mm a"
        return Instant.ofEpochSecond(epochSeconds.coerceAtLeast(0))
            .atZone(zone)
            .format(DateTimeFormatter.ofPattern(pattern, locale))
            .replace(' ', '\u00A0')
    }
}

internal fun nextRetryText(messages: List<org.monogram.core.models.Message>): String? =
    messages.firstOrNull { it.failed && it.outgoing }?.text?.trim()?.takeIf { it.isNotEmpty() }
