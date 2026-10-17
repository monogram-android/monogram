package org.monogram.core.models

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private const val RELATIVE = 1
private const val SHORT_TIME = 1 shl 1
private const val LONG_TIME = 1 shl 2
private const val SHORT_DATE = 1 shl 3
private const val LONG_DATE = 1 shl 4
private const val DAY_OF_WEEK = 1 shl 5

fun formatRichDate(
    url: String?,
    zone: ZoneId,
    locale: Locale,
    now: Instant = Instant.now(),
    dateAtTime: (String, String) -> String = { date, time -> "$date at $time" },
    weekAndRest: (String, String) -> String = { week, rest -> "$week, $rest" },
): String? {
    val raw = url?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val parts = raw.split('|', limit = 2)
    val epoch = parts[0].toLongOrNull() ?: return null
    if (epoch <= 0L) return null
    val flags = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val relative = flags and RELATIVE != 0
    val shortTime = flags and SHORT_TIME != 0
    val longTime = flags and LONG_TIME != 0
    val shortDate = flags and SHORT_DATE != 0
    val longDate = flags and LONG_DATE != 0
    val dayOfWeek = flags and DAY_OF_WEEK != 0
    // LocaleController.formatEntityFormattedDate: a relative flag replaces the whole value.
    if (relative && flags != 0) return formatRelativeRichDate(epoch, now, locale)
    val zoned = Instant.ofEpochSecond(epoch).atZone(zone)
    val forceFull = flags == 0
    val week = if (dayOfWeek) zoned.format(DateTimeFormatter.ofPattern("EEEE", locale)) else ""
    val date = when {
        forceFull || longDate -> zoned.format(DateTimeFormatter.ofPattern("d MMMM yyyy", locale))
        shortDate -> zoned.format(DateTimeFormatter.ofPattern("dd.MM.yy", locale))
        else -> ""
    }
    val time = when {
        longTime || (forceFull && zoned.second != 0) ->
            zoned.format(DateTimeFormatter.ofPattern("HH:mm:ss", locale))

        shortTime || forceFull -> zoned.format(DateTimeFormatter.ofPattern("HH:mm", locale))
        else -> ""
    }
    val body = when {
        date.isNotEmpty() && time.isNotEmpty() -> dateAtTime(date, time)
        date.isNotEmpty() -> date
        else -> time
    }
    return when {
        week.isNotEmpty() && body.isNotEmpty() -> weekAndRest(week, body)
        week.isNotEmpty() -> week
        body.isNotEmpty() -> body
        else -> zoned.format(DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", locale))
    }
}

internal fun formatRelativeRichDate(epochSeconds: Long, now: Instant, locale: Locale): String {
    icuRelative(epochSeconds, now, locale)?.let { return it }
    return englishRelative(epochSeconds, now)
}

private fun icuRelative(epochSeconds: Long, now: Instant, locale: Locale): String? {
    return runCatching {
        val formatter = android.icu.text.RelativeDateTimeFormatter.getInstance(locale)
        val delta = epochSeconds - now.epochSecond
        val absSeconds = abs(delta)
        if (absSeconds < 1L) {
            return formatter.format(
                android.icu.text.RelativeDateTimeFormatter.Direction.PLAIN,
                android.icu.text.RelativeDateTimeFormatter.AbsoluteUnit.NOW,
            )
        }
        val (value, unit) = relativeBucket(absSeconds)
        val direction = if (delta > 0) {
            android.icu.text.RelativeDateTimeFormatter.Direction.NEXT
        } else {
            android.icu.text.RelativeDateTimeFormatter.Direction.LAST
        }
        formatter.format(value.toDouble(), direction, unit).takeIf { it.isNotBlank() }
    }.getOrNull()
}

private fun relativeBucket(
    absSeconds: Long,
): Pair<Long, android.icu.text.RelativeDateTimeFormatter.RelativeUnit> {
    val seconds = android.icu.text.RelativeDateTimeFormatter.RelativeUnit.SECONDS
    val minutes = android.icu.text.RelativeDateTimeFormatter.RelativeUnit.MINUTES
    val hours = android.icu.text.RelativeDateTimeFormatter.RelativeUnit.HOURS
    val days = android.icu.text.RelativeDateTimeFormatter.RelativeUnit.DAYS
    val months = android.icu.text.RelativeDateTimeFormatter.RelativeUnit.MONTHS
    val years = android.icu.text.RelativeDateTimeFormatter.RelativeUnit.YEARS
    val (count, bucket) = when {
        absSeconds < 60L -> absSeconds to seconds
        absSeconds < 3_600L -> absSeconds / 60L to minutes
        absSeconds < 86_400L -> absSeconds / 3_600L to hours
        absSeconds < 30L * 86_400L -> absSeconds / 86_400L to days
        absSeconds < 365L * 86_400L -> absSeconds / (30L * 86_400L) to months
        else -> absSeconds / (365L * 86_400L) to years
    }
    return count.coerceAtLeast(1L) to bucket
}

private fun englishRelative(epochSeconds: Long, now: Instant): String {
    val delta = epochSeconds - now.epochSecond
    val absSeconds = abs(delta)
    if (absSeconds < 1L) return "now"
    val (count, unit) = when {
        absSeconds < 60L -> absSeconds to "second"
        absSeconds < 3_600L -> absSeconds / 60L to "minute"
        absSeconds < 86_400L -> absSeconds / 3_600L to "hour"
        absSeconds < 30L * 86_400L -> absSeconds / 86_400L to "day"
        absSeconds < 365L * 86_400L -> absSeconds / (30L * 86_400L) to "month"
        else -> absSeconds / (365L * 86_400L) to "year"
    }
    val amount = count.coerceAtLeast(1L)
    val word = if (amount == 1L) unit else "${unit}s"
    return if (delta > 0) "in $amount $word" else "$amount $word ago"
}

fun replaceRichDates(
    text: String,
    entities: List<TextEntity>,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    now: Instant = Instant.now(),
    dateAtTime: (String, String) -> String = { date, time -> "$date at $time" },
    weekAndRest: (String, String) -> String = { week, rest -> "$week, $rest" },
): Pair<String, List<TextEntity>> {
    val edits = entities.mapNotNull { entity ->
        if (entity.kind != "date") return@mapNotNull null
        val start = entity.offset.coerceIn(0, text.length)
        val end = (entity.offset + entity.length).coerceIn(start, text.length)
        if (start >= end) return@mapNotNull null
        val formatted = formatRichDate(entity.url, zone, locale, now, dateAtTime, weekAndRest)
            ?: return@mapNotNull null
        DateEdit(start, end, formatted)
    }.sortedBy { it.start }
    if (edits.isEmpty()) return text to entities
    val applied = mutableListOf<DateEdit>()
    val out = StringBuilder()
    var cursor = 0
    for (edit in edits) {
        if (edit.start < cursor) continue
        out.append(text, cursor, edit.start)
        val placed = edit.copy(newStart = out.length)
        out.append(edit.replacement)
        applied += placed.copy(newEnd = out.length)
        cursor = edit.end
    }
    if (applied.isEmpty()) return text to entities
    out.append(text, cursor, text.length)
    val replaced = out.toString()
    val shifted = entities.mapNotNull { entity ->
        val start = entity.offset
        val end = entity.offset + entity.length
        val newStart = mapRichDateIndex(start, applied, atEnd = false)
        val newEnd = mapRichDateIndex(end, applied, atEnd = true)
        if (newStart >= newEnd) return@mapNotNull null
        entity.copy(offset = newStart, length = newEnd - newStart)
    }
    return replaced to shifted
}

private data class DateEdit(
    val start: Int,
    val end: Int,
    val replacement: String,
    val newStart: Int = 0,
    val newEnd: Int = 0,
)

private fun mapRichDateIndex(index: Int, edits: List<DateEdit>, atEnd: Boolean): Int {
    var shift = 0
    for (edit in edits) {
        val grown = (edit.newEnd - edit.newStart) - (edit.end - edit.start)
        if (!atEnd && index <= edit.start) return index + shift
        if (atEnd && index < edit.start) return index + shift
        if (index >= edit.end) {
            shift += grown
            continue
        }
        return if (atEnd) edit.newEnd else edit.newStart
    }
    return index + shift
}
