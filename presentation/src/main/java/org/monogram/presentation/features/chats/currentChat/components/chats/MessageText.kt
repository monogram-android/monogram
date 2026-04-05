package org.monogram.presentation.features.chats.currentChat.components.chats

import android.content.ClipData
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import org.monogram.domain.models.MessageEntity
import org.monogram.presentation.features.chats.currentChat.components.chats.model.isBlockElement

@Composable
fun MessageText(
    text: AnnotatedString,
    inlineContent: Map<String, InlineTextContent>,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    entities: List<MessageEntity> = emptyList(),
    isOutgoing: Boolean = false,
    onSpoilerClick: (Int) -> Unit = {},
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
                entities = entities,
                onSpoilerClick = onSpoilerClick,
                onClick = onClick,
                onLongClick = onLongClick,
                linkHandler = linkHandler,
                uriHandler = uriHandler,
                localClipboard = localClipboard,
                context = context
            )
        } else {
            var lastOffset = 0

            blockEntities.forEach { entity ->
                if (entity.offset > lastOffset) {
                    val subText = text.subSequence(lastOffset, entity.offset)
                    if (subText.text.isNotBlank()) {
                        DefaultTextRender(
                            text = subText,
                            inlineContent = inlineContent,
                            style = style,
                            color = color,
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
                    text = text.text,
                    entity = entity,
                    isOutgoing = isOutgoing,
                )

                lastOffset = entity.offset + entity.length
            }

            if (lastOffset < text.length) {
                val subText = text.subSequence(lastOffset, text.length)
                if (subText.text.isNotBlank()) {
                    DefaultTextRender(
                        text = subText,
                        inlineContent = inlineContent,
                        style = style,
                        color = color,
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

@Composable
private fun DefaultTextRender(
    text: AnnotatedString,
    inlineContent: Map<String, InlineTextContent>,
    style: TextStyle,
    color: Color,
    entities: List<MessageEntity>,
    onSpoilerClick: (Int) -> Unit,
    onClick: (Offset) -> Unit,
    onLongClick: (Offset) -> Unit,
    linkHandler: (String) -> Unit,
    uriHandler: androidx.compose.ui.platform.UriHandler,
    localClipboard: Clipboard,
    context: android.content.Context
) {
    val layoutResult = remember { mutableStateOf<TextLayoutResult?>(null) }
    val spoilerColor = if (color != Color.Unspecified) color else LocalContentColor.current

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
                                        linkHandler("https://t.me/$username")
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
        onTextLayout = { layoutResult.value = it }
    )
}
