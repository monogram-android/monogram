package org.monogram.presentation.features.chats.currentChat.components.chats

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageEntityType
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.features.stickers.ui.view.StickerImage

@Composable
fun rememberMessageInlineContent(
    entities: List<MessageEntity>,
    fontSize: Float
): Map<String, InlineTextContent> {
    return remember(entities, fontSize) {
        val map = mutableMapOf<String, InlineTextContent>()
        val emojiEntities = entities.filter { it.type is MessageEntityType.CustomEmoji }.sortedBy { it.offset }
        emojiEntities.forEachIndexed { index, entity ->
            val emojiSize = (fontSize * 1.25f).sp
            map["emoji_$index"] = InlineTextContent(
                Placeholder(emojiSize, emojiSize, PlaceholderVerticalAlign.Center)
            ) {
                StickerImage(
                    path = (entity.type as MessageEntityType.CustomEmoji).path,
                    modifier = Modifier.size((fontSize * 1.25f).dp)
                )
            }
        }
        map
    }
}

@Composable
fun buildAnnotatedMessageTextWithEmoji(
    text: String,
    entities: List<MessageEntity>,
    isOutgoing: Boolean = false,
    revealedSpoilers: List<Int> = emptyList(),
    appPreferences: AppPreferences = koinInject()
): AnnotatedString {
    val context = LocalContext.current
    val emojiStyle by appPreferences.emojiStyle.collectAsState()
    val emojiFontFamily = remember(context, emojiStyle) { getEmojiFontFamily(context, emojiStyle) }

    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
    val codeTextColor = MaterialTheme.colorScheme.primary

    return remember(text, entities, isOutgoing, revealedSpoilers, emojiFontFamily, linkColor, codeBackgroundColor, codeTextColor) {
        val emojiEntities = entities.filter { it.type is MessageEntityType.CustomEmoji }.sortedBy { it.offset }
        val otherEntities = entities.filter { it.type !is MessageEntityType.CustomEmoji }

        buildAnnotatedString {
            var currentPos = 0
            val indexMapping = mutableMapOf<Int, Int>()

            emojiEntities.forEachIndexed { index, entity ->
                val safeStart = entity.offset.coerceIn(0, text.length)
                val safeEnd = (entity.offset + entity.length).coerceIn(safeStart, text.length)

                if (safeStart > currentPos) {
                    val segment = text.substring(currentPos, safeStart)
                    segment.forEachIndexed { i, _ ->
                        indexMapping[currentPos + i] = this.length
                        append(segment[i])
                    }
                }

                if (safeStart >= currentPos) {
                    indexMapping[safeStart] = this.length
                    appendInlineContent("emoji_$index", "[emoji]")
                    currentPos = safeEnd
                }
            }

            if (currentPos < text.length) {
                val segment = text.substring(currentPos)
                segment.forEachIndexed { i, _ ->
                    indexMapping[currentPos + i] = this.length
                    append(segment[i])
                }
            }
            indexMapping[text.length] = this.length

            if (emojiFontFamily != FontFamily.Default) {
                addEmojiStyle(this.toAnnotatedString().text, emojiFontFamily)
            }

            otherEntities.forEachIndexed { index, entity ->
                val safeStart = entity.offset.coerceIn(0, text.length)
                val safeEnd = (entity.offset + entity.length).coerceIn(safeStart, text.length)
                val start = indexMapping[safeStart] ?: return@forEachIndexed
                val end = indexMapping[safeEnd] ?: indexMapping[text.length] ?: return@forEachIndexed
                if (start >= end) return@forEachIndexed

                when (val type = entity.type) {
                    is MessageEntityType.Bold -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
                    is MessageEntityType.Italic -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
                    is MessageEntityType.Underline -> addStyle(
                        SpanStyle(textDecoration = TextDecoration.Underline),
                        start,
                        end
                    )

                    is MessageEntityType.Strikethrough -> addStyle(
                        SpanStyle(textDecoration = TextDecoration.LineThrough),
                        start,
                        end
                    )

                    is MessageEntityType.Spoiler -> {
                        val isRevealed = revealedSpoilers.contains(index)
                        if (isRevealed) {
                            addStyle(SpanStyle(background = Color.Gray.copy(alpha = 0.2f)), start, end)
                            addStringAnnotation("SPOILER_REVEALED", index.toString(), start, end)
                        } else {
                            addStyle(
                                SpanStyle(color = Color.Transparent),
                                start,
                                end
                            )
                            addStringAnnotation("SPOILER_UNREVEALED", index.toString(), start, end)
                        }
                        addStringAnnotation("SPOILER", index.toString(), start, end)
                    }

                    is MessageEntityType.Code -> {
                        addStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = codeBackgroundColor,
                                color = codeTextColor
                            ), start, end
                        )
                        addStringAnnotation(
                            "COPY",
                            text.safeSubstring(safeStart, safeEnd),
                            start,
                            end
                        )
                    }

                    is MessageEntityType.Pre -> {
                        addStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = codeBackgroundColor,
                                color = codeTextColor
                            ), start, end
                        )
                        addStringAnnotation(
                            "COPY",
                            text.safeSubstring(safeStart, safeEnd),
                            start,
                            end
                        )
                    }

                    is MessageEntityType.TextUrl -> {
                        addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), start, end)
                        addStringAnnotation("URL", type.url, start, end)
                    }

                    is MessageEntityType.Url -> {
                        addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), start, end)
                        addStringAnnotation("URL", text.safeSubstring(safeStart, safeEnd), start, end)
                    }

                    is MessageEntityType.Mention -> {
                        addStyle(SpanStyle(color = linkColor), start, end)
                        addStringAnnotation(
                            "MENTION",
                            text.safeSubstring(safeStart, safeEnd),
                            start,
                            end
                        )
                    }

                    is MessageEntityType.TextMention -> {
                        addStyle(SpanStyle(color = linkColor), start, end)
                        addStringAnnotation(
                            "TEXT_MENTION",
                            type.userId.toString(),
                            start,
                            end
                        )
                    }

                    is MessageEntityType.Hashtag -> {
                        addStyle(SpanStyle(color = linkColor), start, end)
                        addStringAnnotation(
                            "HASHTAG",
                            text.safeSubstring(safeStart, safeEnd),
                            start,
                            end
                        )
                    }

                    else -> {}
                }
            }
        }
    }
}

private fun String.safeSubstring(start: Int, end: Int): String {
    if (isEmpty()) return ""
    val safeStart = start.coerceIn(0, length)
    val safeEnd = end.coerceIn(safeStart, length)
    return substring(safeStart, safeEnd)
}
