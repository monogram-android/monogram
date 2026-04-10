package org.monogram.presentation.features.chats.currentChat.components.chats

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageEntityType
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageSendingState
import org.monogram.presentation.core.util.DateFormatManager
import org.monogram.presentation.core.util.EmojiStyle
import org.monogram.presentation.features.chats.currentChat.components.channels.formatViews
import java.io.File
import java.text.BreakIterator
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.log10
import kotlin.math.pow

val LocalLinkHandler = staticCompositionLocalOf<(String) -> Unit> {
    { _ -> }
}

fun formatTime(ts: Int, timeFormat: String): String =
    SimpleDateFormat(timeFormat, Locale.getDefault()).format(Date(ts.toLong() * 1000))

fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", m, s)
}
fun formatFileSize(size: Long, isDownloading: Boolean, downloadProgress: Float): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")

    fun format(value: Double): String {
        if (value <= 0) return "0 B"
        val digitGroups = (log10(value) / log10(1024.0)).toInt()
        return String.format(
            Locale.US,
            "%.1f %s",
            value / 1024.0.pow(digitGroups.toDouble()),
            units[digitGroups]
        )
    }

    return if (isDownloading) {
        val downloaded = size * downloadProgress
        "${format(downloaded.toDouble())} / ${format(size.toDouble())}"
    } else {
        format(size.toDouble())
    }
}

fun getEmojiFontFileName(style: EmojiStyle): String? = when (style) {
    EmojiStyle.APPLE -> "apple.ttf"
    EmojiStyle.TWITTER -> "twemoji.ttf"
    EmojiStyle.WINDOWS -> "win11.ttf"
    EmojiStyle.CATMOJI -> "catmoji.ttf"
    EmojiStyle.NOTO -> "notoemoji.ttf"
    EmojiStyle.SYSTEM -> null
}

@Composable
fun getEmojiFontFamily(style: EmojiStyle): FontFamily {
    val context = LocalContext.current
    return getEmojiFontFamily(context, style)
}

fun getEmojiFontFamily(context: Context, style: EmojiStyle): FontFamily {
    val fileName = getEmojiFontFileName(style) ?: return FontFamily.Default
    val file = File(context.filesDir, "fonts/$fileName")
    return if (file.exists()) {
        try {
            FontFamily(Font(file))
        } catch (e: Exception) {
            FontFamily.Default
        }
    } else {
        FontFamily.Default
    }
}

fun AnnotatedString.Builder.addEmojiStyle(text: String, emojiFontFamily: FontFamily) {
    var i = 0
    var currentBlockStart = 0
    var isBlockEmoji = false

    while (i < text.length) {
        val codePoint = text.codePointAt(i)
        val charCount = Character.charCount(codePoint)

        val isCurrentCharEmoji = isEmojiLegacy(codePoint)

        if (i == 0) {
            isBlockEmoji = isCurrentCharEmoji
        }

        if (isBlockEmoji != isCurrentCharEmoji) {
            val fontToUse = if (isBlockEmoji) emojiFontFamily else FontFamily.Default
            addStyle(SpanStyle(fontFamily = fontToUse), currentBlockStart, i)

            currentBlockStart = i
            isBlockEmoji = isCurrentCharEmoji
        }

        i += charCount
    }

    val finalFont = if (isBlockEmoji) emojiFontFamily else FontFamily.Default
    addStyle(SpanStyle(fontFamily = finalFont), currentBlockStart, text.length)
}

fun isBigEmoji(text: String, entities: List<MessageEntity>): Boolean {
    val emojiEntities = entities.filter { it.type is MessageEntityType.CustomEmoji }
    val otherEntities = entities.filter { it.type !is MessageEntityType.CustomEmoji }

    if (otherEntities.isNotEmpty()) return false

    if (emojiEntities.size == 1) {
        val entity = emojiEntities[0]
        val textBefore = text.substring(0, entity.offset)
        val textAfter = text.substring(entity.offset + entity.length)
        return textBefore.trim().isEmpty() && textAfter.trim().isEmpty()
    }

    if (emojiEntities.isNotEmpty()) return false

    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false

    var i = 0
    while (i < trimmed.length) {
        val codePoint = trimmed.codePointAt(i)
        if (!isEmojiLegacy(codePoint)) return false
        i += Character.charCount(codePoint)
    }

    val it = BreakIterator.getCharacterInstance()
    it.setText(trimmed)
    var count = 0
    while (it.next() != BreakIterator.DONE) {
        count++
    }
    return count == 1
}

fun isEmojiLegacy(codePoint: Int): Boolean {
    return when (codePoint) {
        in 0x1F600..0x1F64F -> true // Emoticons (😀, 😭, etc.)
        in 0x1F300..0x1F5FF -> true // Misc Symbols and Pictographs (🍔, 🌺, 🎤)
        in 0x1F680..0x1F6FF -> true // Transport and Map (🚀, 🚗, 🚲)
        in 0x1F900..0x1F9FF -> true // Supplemental Symbols and Pictographs (🦕, 🥨, 🧗)
        in 0x1FA70..0x1FAFF -> true // Symbols and Pictographs Extended-A (🩰, 🪖, 🫧 - including new Unicode 15)
        in 0x1FA00..0x1FA6F -> true // Chess, Mahjong, and other extensions (🩲, 🪰)

        in 0x2600..0x26FF -> true   // Misc Symbols (☔, ☕, ♻️, ♀️)
        in 0x2700..0x27BF -> true   // Dingbats (✂️, ✉️, ✨, ❄️)
        in 0x2B50..0x2B55 -> true   // Stars and Circles (⭐, ⭕)
        in 0x2300..0x23FF -> true   // Misc Technical (⌚, ⏰, ⌨️, ⏩ - Watch, Hourglass, Arrows)
        in 0x2194..0x2199 -> true   // Arrows often used as emoji (↔️, ↕️)
        in 0x2B05..0x2B07 -> true   // Large Arrows (⬅️, ⬇️)
        in 0x2934..0x2935 -> true   // Curving Arrows (⤴️)
        in 0x3297..0x3299 -> true   // Circled CJK (㊙️, ㊗️)

        in 0x1F1E6..0x1F1FF -> true // Regional Indicator Symbols (The letters that make Flags 🇺🇸, 🇮🇹)
        in 0x1F191..0x1F19A -> true // Squared ID, VS, etc. (🆔, 🆘)
        0x200D -> true              // Zero Width Joiner (Essential for complex emojis like 👨‍👩‍👧‍👦)
        0xFE0F -> true              // Variation Selector-16 (Forces emoji style on text chars)

        in 0x1F004..0x1F0CF -> true // Mahjong & Playing Cards (🀄, 🃏)

        else -> false
    }
}

fun normalizeUrl(url: String): String {
    return if (url.startsWith("http://") || url.startsWith("https://")) {
        url
    } else {
        "https://$url"
    }
}

@Composable
fun MessageMetadata(
    msg: MessageModel,
    isOutgoing: Boolean,
    contentColor: Color
) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val views = msg.viewCount ?: msg.views
        if (views != null && views > 0) {
            Icon(
                imageVector = Icons.Outlined.Visibility,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = contentColor
            )
            Text(
                text = formatViews(context, views),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = contentColor
            )
        }

        if (msg.editDate > 0) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Edited",
                modifier = Modifier.size(12.dp),
                tint = contentColor
            )
        }
        val dateFormatManager: DateFormatManager = koinInject()
        val timeFormat = dateFormatManager.getHourMinuteFormat()
        Text(
            text = formatTime(msg.date, timeFormat),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = contentColor
        )
        if (isOutgoing) {
            MessageSendingStatusIcon(
                sendingState = msg.sendingState,
                isRead = msg.isRead,
                baseColor = contentColor,
                size = 13.dp,
                usePrimaryForRead = contentColor != Color.White
            )
        }
    }
}

@Composable
fun MessageSendingStatusIcon(
    sendingState: MessageSendingState?,
    isRead: Boolean,
    baseColor: Color,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 14.dp,
    usePrimaryForRead: Boolean = true
) {
    val targetTint = when {
        sendingState is MessageSendingState.Failed -> MaterialTheme.colorScheme.error
        isRead && usePrimaryForRead -> MaterialTheme.colorScheme.primary
        else -> baseColor
    }
    val tint = animateColorAsState(targetValue = targetTint, animationSpec = tween(220), label = "SendingTint").value

    AnimatedContent(
        targetState = sendingState to isRead,
        transitionSpec = {
            val incomingFromBottom = targetState.first == null && initialState.first is MessageSendingState.Pending
            val enter = fadeIn(tween(200)) +
                    slideInVertically(tween(220)) { fullHeight -> if (incomingFromBottom) fullHeight / 2 else -fullHeight / 2 }
            val exit = fadeOut(tween(150)) +
                    slideOutVertically(tween(170)) { fullHeight -> if (incomingFromBottom) -fullHeight / 2 else fullHeight / 2 }
            enter.togetherWith(exit).using(SizeTransform(clip = false))
        },
        label = "SendingState"
    ) { (state, read) ->
        val icon = when (state) {
            is MessageSendingState.Pending -> Icons.Default.Schedule
            is MessageSendingState.Failed -> Icons.Default.Error
            null -> if (read) Icons.Default.DoneAll else Icons.Default.Check
        }

        val pendingRotation = if (state is MessageSendingState.Pending) {
            val transition = rememberInfiniteTransition(label = "PendingRotation")
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "PendingRotationValue"
            ).value
        } else {
            0f
        }

        val settledScale = animateFloatAsState(
            targetValue = if (state is MessageSendingState.Pending) 1f else 1.06f,
            animationSpec = tween(220),
            label = "SendingScale"
        ).value

        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = modifier
                .size(size)
                .graphicsLayer {
                    rotationZ = pendingRotation
                    scaleX = settledScale
                    scaleY = settledScale
                },
            tint = tint
        )
    }
}
