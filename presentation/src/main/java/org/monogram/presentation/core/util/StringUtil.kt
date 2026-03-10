package org.monogram.presentation.core.util

import android.text.format.DateUtils
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import org.monogram.domain.models.*
import java.text.SimpleDateFormat
import java.util.*

fun formatLastSeen(lastSeen: Long?): String {
    if (lastSeen == null || lastSeen <= 0L) return "Last seen recently"

    val now = System.currentTimeMillis()
    val diff = now - lastSeen

    if (diff < 0) return "Last seen just now"

    return when {
        diff < 60 * 1000L -> "Last seen just now"
        diff < 60 * 60 * 1000L -> {
            val minutes = diff / (60 * 1000L)
            "Last seen $minutes minute${if (minutes != 1L) "s" else ""} ago"
        }

        DateUtils.isToday(lastSeen) -> {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastSeen))
            "Last seen at $time"
        }

        isYesterday(lastSeen) -> {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastSeen))
            "Last seen yesterday at $time"
        }

        else -> {
            val date = SimpleDateFormat("dd.MM.yy", Locale.getDefault()).format(Date(lastSeen))
            "Last seen $date"
        }
    }
}

private fun isYesterday(timestamp: Long): Boolean {
    return DateUtils.isToday(timestamp + DateUtils.DAY_IN_MILLIS)
}

fun getUserStatusText(user: UserModel?): String {
    if (user == null) return "Offline"
    if (user.type == UserTypeEnum.BOT) return "Bot"

    return when (user.userStatus) {
        UserStatusType.ONLINE -> "Online"
        UserStatusType.OFFLINE -> formatLastSeen(user.lastSeen)
        UserStatusType.RECENTLY -> "Last seen recently"
        UserStatusType.LAST_WEEK -> "Last seen within a week"
        UserStatusType.LAST_MONTH -> "Last seen within a month"
        else -> "Long time ago"
    }
}

fun buildRichText(
    richText: RichText,
    linkColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        append(richText.text)
        richText.entities.forEach { entity ->
            val style = when (val type = entity.type) {
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
fun formatPhoneGlobal(phone: String): String {
    val digits = phone.filter { it.isDigit() }

    if (digits.isEmpty()) return ""

    if (digits.length == 11 && (digits.startsWith("7") || digits.startsWith("8"))) {
        val d = digits
        return "+7 (${d.substring(1, 4)}) ${d.substring(4, 7)}-${d.substring(7, 9)}-${d.substring(9, 11)}"
    }

    return "+$digits"
}

fun formatMaskedGlobal(phone: String): String {
    val digits = phone.filter { it.isDigit() }

    if (digits.length < 5) return "****"

    if (digits.length == 11 && (digits.startsWith("7") || digits.startsWith("8"))) {
        return "+7 (***) ***-**-${digits.takeLast(2)}"
    }

    val visibleCount = 4
    val maskedCount = digits.length - visibleCount
    val stars = "*".repeat(maskedCount)

    return "+$stars${digits.takeLast(visibleCount)}"
}