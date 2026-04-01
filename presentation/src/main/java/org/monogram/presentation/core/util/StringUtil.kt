package org.monogram.presentation.core.util

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import org.monogram.domain.models.*
import org.monogram.presentation.R
import java.text.SimpleDateFormat
import java.util.*

fun formatLastSeen(lastSeen: Long?, context: Context): String {
    if (lastSeen == null || lastSeen <= 0L) return context.getString(R.string.last_seen_recently)

    val now = System.currentTimeMillis()
    val diff = now - lastSeen

    if (diff < 0) return context.getString(R.string.last_seen_just_now)

    return when {
        diff < 60 * 1000L -> context.getString(R.string.last_seen_just_now)
        diff < 60 * 60 * 1000L -> {
            val minutes = (diff / (60 * 1000L)).toInt()
            context.resources.getQuantityString(R.plurals.last_seen_minutes_ago, minutes, minutes)
        }

        DateUtils.isToday(lastSeen) -> {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastSeen))
            context.getString(R.string.last_seen_at, time)
        }

        isYesterday(lastSeen) -> {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastSeen))
            context.getString(R.string.last_seen_yesterday_at, time)
        }

        else -> {
            val date = SimpleDateFormat("dd.MM.yy", Locale.getDefault()).format(Date(lastSeen))
            context.getString(R.string.last_seen_date, date)
        }
    }
}

@Composable
fun rememberUserStatusText(user: UserModel?): String {
    if (user == null) return stringResource(R.string.status_offline)
    if (user.type == UserTypeEnum.BOT) return stringResource(R.string.status_bot)

    val context = LocalContext.current
    return remember(user.userStatus, user.lastSeen) {
        when (user.userStatus) {
            UserStatusType.ONLINE -> context.getString(R.string.status_online)
            UserStatusType.OFFLINE -> formatLastSeen(user.lastSeen, context)
            UserStatusType.RECENTLY -> context.getString(R.string.last_seen_recently)
            UserStatusType.LAST_WEEK -> context.getString(R.string.last_seen_within_week)
            UserStatusType.LAST_MONTH -> context.getString(R.string.last_seen_within_month)
            else -> context.getString(R.string.last_seen_long_time_ago)
        }
    }
}

private fun isYesterday(timestamp: Long): Boolean {
    return DateUtils.isToday(timestamp + DateUtils.DAY_IN_MILLIS)
}

fun getUserStatusText(user: UserModel?, context: Context): String {
    if (user == null) return context.getString(R.string.status_offline)
    if (user.type == UserTypeEnum.BOT) return context.getString(R.string.status_bot)

    return when (user.userStatus) {
        UserStatusType.ONLINE -> context.getString(R.string.status_online)
        UserStatusType.OFFLINE -> formatLastSeen(user.lastSeen, context)
        UserStatusType.RECENTLY -> context.getString(R.string.last_seen_recently)
        UserStatusType.LAST_WEEK -> context.getString(R.string.last_seen_within_week)
        UserStatusType.LAST_MONTH -> context.getString(R.string.last_seen_within_month)
        else -> context.getString(R.string.last_seen_long_time_ago)
    }
}

fun buildRichText(
    richText: RichText,
    linkColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        append(richText.text)
        richText.entities.forEach { entity ->
            val style = when (entity.type) {
                is MessageEntityType.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
                is MessageEntityType.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
                is MessageEntityType.Underline -> SpanStyle(textDecoration = TextDecoration.Underline)
                is MessageEntityType.Strikethrough -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                is MessageEntityType.Spoiler -> SpanStyle(background = Color.Gray.copy(alpha = 0.3f))
                is MessageEntityType.Code -> SpanStyle(background = Color.LightGray.copy(alpha = 0.2f))
                is MessageEntityType.Pre -> SpanStyle(background = Color.LightGray.copy(alpha = 0.2f))
                is MessageEntityType.TextUrl -> SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                is MessageEntityType.Url -> SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                is MessageEntityType.Mention -> SpanStyle(color = linkColor)
                is MessageEntityType.Hashtag -> SpanStyle(color = linkColor)
                is MessageEntityType.BotCommand -> SpanStyle(color = linkColor)
                is MessageEntityType.Email -> SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                is MessageEntityType.PhoneNumber -> SpanStyle(
                    color = linkColor,
                    textDecoration = TextDecoration.Underline
                )

                is MessageEntityType.BankCardNumber -> SpanStyle(
                    color = linkColor,
                    textDecoration = TextDecoration.Underline
                )

                is MessageEntityType.TextMention -> SpanStyle(color = linkColor)
                is MessageEntityType.CustomEmoji -> null
                else -> null
            }

            if (style != null) {
                addStyle(style, entity.offset, entity.offset + entity.length)
            }

            when (val type = entity.type) {
                is MessageEntityType.TextUrl -> {
                    addStringAnnotation("URL", type.url, entity.offset, entity.offset + entity.length)
                }

                is MessageEntityType.Url -> {
                    val url = richText.text.substring(entity.offset, entity.offset + entity.length)
                    addStringAnnotation("URL", url, entity.offset, entity.offset + entity.length)
                }

                else -> {}
            }
        }
    }
}

fun buildRichText(
    text: String,
    linkColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        var currentIndex = 0
        val boldRegex = "\\*\\*(.*?)\\*\\*".toRegex()
        val urlPattern = Regex("(https?://\\S+|t\\.me/\\S+)")
        val mentionPattern = Regex("@[a-zA-Z0-9_]+")

        val boldMatches = boldRegex.findAll(text).toList()

        if (boldMatches.isEmpty()) {
            append(text)
        } else {
            boldMatches.forEach { matchResult ->
                val range = matchResult.range
                if (range.first > currentIndex) {
                    append(text.substring(currentIndex, range.first))
                }

                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(matchResult.groupValues[1])
                }

                currentIndex = range.last + 1
            }

            if (currentIndex < text.length) {
                append(text.substring(currentIndex))
            }
        }

        val fullText = this.toAnnotatedString().text

        urlPattern.findAll(fullText).forEach { result ->
            addStyle(
                style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                start = result.range.first,
                end = result.range.last + 1
            )
            addStringAnnotation(
                tag = "URL",
                annotation = result.value,
                start = result.range.first,
                end = result.range.last + 1
            )
        }

        mentionPattern.findAll(fullText).forEach { result ->
            addStyle(
                style = SpanStyle(color = linkColor),
                start = result.range.first,
                end = result.range.last + 1
            )
            addStringAnnotation(
                tag = "URL",
                annotation = "https://t.me/${result.value.substring(1)}",
                start = result.range.first,
                end = result.range.last + 1
            )
        }
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }