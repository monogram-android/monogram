package org.monogram.presentation.features.chats.currentChat.components.chats

import android.graphics.RuntimeShader
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageEntityType

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
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val linkHandler = LocalLinkHandler.current

    val blockEntities = entities
        .filter { it.type is MessageEntityType.Pre }
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
                clipboardManager = clipboardManager,
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
                            clipboardManager = clipboardManager,
                            context = context
                        )
                    }
                }

                val codeType = entity.type as MessageEntityType.Pre
                val codeRawText = text.text.substring(entity.offset, entity.offset + entity.length)

                CodeBlock(
                    text = codeRawText,
                    language = codeType.language,
                    isOutgoing = isOutgoing
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
                        clipboardManager = clipboardManager,
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
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
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
            RuntimeShader(SpoilerShader.SHADER_CODE)
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
                    entities.forEachIndexed { index, entity ->
                        if (entity.type is MessageEntityType.Spoiler) {
                            val spoilerAnnotation = text.getStringAnnotations("SPOILER_UNREVEALED", 0, text.length)
                                .find { it.item == index.toString() }

                            if (spoilerAnnotation != null) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null) {
                                    drawSpoilerEffect(
                                        layoutResult = result,
                                        start = spoilerAnnotation.start,
                                        end = spoilerAnnotation.end,
                                        shader = shader,
                                        time = time,
                                        color = spoilerColor.copy(alpha = 0.5f)
                                    )
                                } else {
                                    val path = result.getPathForRange(spoilerAnnotation.start, spoilerAnnotation.end)
                                    drawPath(path, color = spoilerColor.copy(alpha = 0.5f))
                                }
                            }
                        }
                    }
                }
            }
            .pointerInput(text) {
                detectTapGestures(
                    onTap = { offset ->
                        var consumed = false
                        layoutResult.value?.let { result ->
                            val position = result.getOffsetForPosition(offset)
                            text.getStringAnnotations(position, position).firstOrNull()?.let { annotation ->
                                when (annotation.tag) {
                                    "URL" -> {
                                        val url = normalizeUrl(annotation.item)
                                        linkHandler(url)
                                        consumed = true
                                    }

                                    "SPOILER", "SPOILER_REVEALED", "SPOILER_UNREVEALED" -> {
                                        onSpoilerClick(annotation.item.toInt())
                                        consumed = true
                                    }

                                    "COPY" -> {
                                        clipboardManager.setText(AnnotatedString(annotation.item))
                                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
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
