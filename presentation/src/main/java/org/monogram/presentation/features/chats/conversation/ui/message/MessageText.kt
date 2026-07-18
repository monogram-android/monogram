package org.monogram.presentation.features.chats.conversation.ui.message

import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageEntityType
import org.monogram.domain.repository.TelegramLinkRepository
import org.monogram.presentation.features.chats.conversation.ui.message.model.isBlockElement

internal data class MessageTextLayoutInfo(
    val width: Int,
    val height: Int,
    val lineCount: Int,
    val lastLineLeft: Float,
    val lastLineRight: Float,
    val lastLineBottom: Float,
    val lastLineDirection: ResolvedTextDirection
)

@Composable
internal fun MessageText(
    text: AnnotatedString,
    rawText: String = text.text,
    inlineContent: Map<String, InlineTextContent>,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    entities: List<MessageEntity> = emptyList(),
    isOutgoing: Boolean = false,
    onSpoilerClick: (Int) -> Unit = {},
    onTextLayoutInfo: (MessageTextLayoutInfo?) -> Unit = {},
    onClick: (Offset) -> Unit = {},
    onLongClick: (Offset) -> Unit = {}
) {
    val uriHandler = LocalUriHandler.current
    val localClipboard = LocalClipboard.current
    val context = LocalContext.current
    val linkHandler = LocalLinkHandler.current

    val blockEntities = entities
        .filter { it.type.isBlockElement() }
        .sortedBy { it.offset }

    Column(modifier = modifier) {
        if (blockEntities.isEmpty()) {
            DefaultTextRender(
                text = text,
                inlineContent = inlineContent,
                style = style,
                color = color,
                maxLines = maxLines,
                overflow = overflow,
                entities = entities,
                onSpoilerClick = onSpoilerClick,
                onTextLayoutInfo = onTextLayoutInfo,
                onClick = onClick,
                onLongClick = onLongClick,
                linkHandler = linkHandler,
                uriHandler = uriHandler,
                localClipboard = localClipboard,
                context = context
            )
        } else {
            SideEffect {
                onTextLayoutInfo(null)
            }

            var lastOffset = 0
            val displayTextLength = text.length

            blockEntities.forEach { entity ->
                val safeLastOffset = rawOffsetToDisplayOffset(
                    rawText = rawText,
                    entities = entities,
                    rawOffset = lastOffset,
                    displayTextLength = displayTextLength
                )
                val safeEntityStart = rawOffsetToDisplayOffset(
                    rawText = rawText,
                    entities = entities,
                    rawOffset = entity.offset,
                    displayTextLength = displayTextLength
                )

                if (safeEntityStart > safeLastOffset) {
                    val subText = text.subSequence(safeLastOffset, safeEntityStart)
                    if (subText.text.isNotBlank()) {
                        DefaultTextRender(
                            text = subText,
                            inlineContent = inlineContent,
                            style = style,
                            color = color,
                            maxLines = maxLines,
                            overflow = overflow,
                            entities = entities,
                            onSpoilerClick = onSpoilerClick,
                            onClick = onClick,
                            onLongClick = onLongClick,
                            linkHandler = linkHandler,
                            uriHandler = uriHandler,
                            localClipboard = localClipboard,
                            context = context
                        )
                    }
                }

                TextBlocks(
                    text = rawText,
                    entities = entities,
                    entity = entity,
                    isOutgoing = isOutgoing,
                )

                val safeEntityEnd = (entity.offset.toLong() + entity.length.toLong())
                    .coerceAtLeast(entity.offset.toLong())
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
                lastOffset = maxOf(lastOffset, safeEntityEnd)
            }

            val safeLastOffset = rawOffsetToDisplayOffset(
                rawText = rawText,
                entities = entities,
                rawOffset = lastOffset,
                displayTextLength = displayTextLength
            )
            if (safeLastOffset < displayTextLength) {
                val subText = text.subSequence(safeLastOffset, displayTextLength)
                if (subText.text.isNotBlank()) {
                    DefaultTextRender(
                        text = subText,
                        inlineContent = inlineContent,
                        style = style,
                        color = color,
                        maxLines = maxLines,
                        overflow = overflow,
                        entities = entities,
                        onSpoilerClick = onSpoilerClick,
                        onClick = onClick,
                        onLongClick = onLongClick,
                        linkHandler = linkHandler,
                        uriHandler = uriHandler,
                        localClipboard = localClipboard,
                        context = context
                    )
                }
            }
        }
    }
}

private fun rawOffsetToDisplayOffset(
    rawText: String,
    entities: List<MessageEntity>,
    rawOffset: Int,
    displayTextLength: Int
): Int {
    val targetOffset = rawOffset.coerceIn(0, rawText.length)
    val emojiEntities = entities
        .filter { it.type is MessageEntityType.CustomEmoji }
        .sortedBy { it.offset }

    var rawPosition = 0
    var displayPosition = 0

    emojiEntities.forEachIndexed { index, entity ->
        val safeStart = entity.offset.coerceIn(0, rawText.length)
        val safeEnd = (entity.offset + entity.length).coerceIn(safeStart, rawText.length)

        if (targetOffset <= safeStart) {
            return (displayPosition + (targetOffset - rawPosition)).coerceIn(0, displayTextLength)
        }

        if (safeStart > rawPosition) {
            displayPosition += safeStart - rawPosition
            rawPosition = safeStart
        }

        val inlinePlaceholderLength = "[emoji]".length
        if (targetOffset <= safeEnd) {
            return (displayPosition + inlinePlaceholderLength).coerceIn(0, displayTextLength)
        }

        displayPosition += inlinePlaceholderLength
        rawPosition = safeEnd
    }

    return (displayPosition + (targetOffset - rawPosition)).coerceIn(0, displayTextLength)
}

@Composable
private fun DefaultTextRender(
    text: AnnotatedString,
    inlineContent: Map<String, InlineTextContent>,
    style: TextStyle,
    color: Color,
    maxLines: Int,
    overflow: TextOverflow,
    entities: List<MessageEntity>,
    onSpoilerClick: (Int) -> Unit,
    onTextLayoutInfo: (MessageTextLayoutInfo) -> Unit = {},
    onClick: (Offset) -> Unit,
    onLongClick: (Offset) -> Unit,
    linkHandler: (String) -> Unit,
    uriHandler: androidx.compose.ui.platform.UriHandler,
    localClipboard: Clipboard,
    context: android.content.Context
) {
    val layoutResult = remember { mutableStateOf<TextLayoutResult?>(null) }
    val spoilerColor = if (color != Color.Unspecified) color else LocalContentColor.current
    val telegramLinkRepository: TelegramLinkRepository = koinInject()
    val scope = rememberCoroutineScope()

    val time by produceState(0f) {
        while (true) {
            withInfiniteAnimationFrameMillis {
                value = it / 1000f
            }
        }
    }

    val shader = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            SpoilerShaderApi33.createShader(SpoilerShader.SHADER_CODE)
        } else null
    }

    Text(
        text = text,
        inlineContent = inlineContent,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
        modifier = Modifier
            .drawBehind {
                layoutResult.value?.let { result ->
                    val unrevealedSpoilers =
                        text.getStringAnnotations("SPOILER_UNREVEALED", 0, text.length)
                    unrevealedSpoilers.forEach { spoilerAnnotation ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null) {
                            drawSpoilerEffectApi33(
                                layoutResult = result,
                                start = spoilerAnnotation.start,
                                end = spoilerAnnotation.end,
                                shader = shader,
                                time = time,
                                color = spoilerColor.copy(alpha = 0.5f)
                            )
                        } else {
                            drawSpoilerEffectFallback(
                                layoutResult = result,
                                start = spoilerAnnotation.start,
                                end = spoilerAnnotation.end,
                                time = time,
                                color = spoilerColor
                            )
                        }
                    }
                }
            }
            .pointerInput(text) {
                detectTapGestures(
                    onTap = { offset ->
                        var consumed = false
                        layoutResult.value?.let { result ->
                            val rawPosition = result.getOffsetForPosition(offset)
                            val position = if (text.isNotEmpty()) rawPosition.coerceIn(
                                0,
                                text.length - 1
                            ) else 0
                            val annotations = buildList {
                                addAll(
                                    text.getStringAnnotations(
                                        position,
                                        (position + 1).coerceAtMost(text.length)
                                    )
                                )
                                if (position > 0) {
                                    addAll(text.getStringAnnotations(position - 1, position))
                                }
                            }

                            val annotation =
                                annotations.firstOrNull { it.tag.startsWith("SPOILER") }
                                    ?: annotations.firstOrNull()

                            annotation?.let {
                                when (annotation.tag) {
                                    "URL" -> {
                                        val url = normalizeUrl(annotation.item)
                                        linkHandler(url)
                                        consumed = true
                                    }

                                    "EMAIL" -> {
                                        uriHandler.openUri("mailto:${annotation.item}")
                                        consumed = true
                                    }

                                    "PHONE" -> {
                                        uriHandler.openUri("tel:${annotation.item}")
                                        consumed = true
                                    }

                                    "SPOILER", "SPOILER_REVEALED", "SPOILER_UNREVEALED" -> {
                                        annotation.item.toIntOrNull()?.let {
                                            onSpoilerClick(it)
                                            consumed = true
                                        }
                                    }

                                    "COPY" -> {
                                        localClipboard.nativeClipboard.setPrimaryClip(
                                            ClipData.newPlainText(
                                                "",
                                                AnnotatedString(annotation.item)
                                            )
                                        )
                                        Toast.makeText(
                                            context,
                                            "Copied to clipboard",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        consumed = true
                                    }

                                    "MENTION" -> {
                                        val username = annotation.item.removePrefix("@")
                                        scope.launch {
                                            linkHandler(telegramLinkRepository.buildUrl(username))
                                        }
                                        consumed = true
                                    }

                                    "TEXT_MENTION" -> {
                                        val userId = annotation.item
                                        linkHandler("tg://user?id=$userId")
                                        consumed = true
                                    }
                                }
                            }
                        }
                        if (!consumed) onClick(offset)
                    },
                    onLongPress = { offset -> onLongClick(offset) }
                )
            },
        onTextLayout = {
            layoutResult.value = it
            onTextLayoutInfo(it.toMessageTextLayoutInfo())
        }
    )
}

private fun TextLayoutResult.toMessageTextLayoutInfo(): MessageTextLayoutInfo {
    val lastLineIndex = (lineCount - 1).coerceAtLeast(0)
    val lineStart = getLineStart(lastLineIndex)

    return MessageTextLayoutInfo(
        width = size.width,
        height = size.height,
        lineCount = lineCount,
        lastLineLeft = getLineLeft(lastLineIndex),
        lastLineRight = getLineRight(lastLineIndex),
        lastLineBottom = getLineBottom(lastLineIndex),
        lastLineDirection = getParagraphDirection(lineStart)
    )
}
