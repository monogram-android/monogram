package org.monogram.presentation.features.chats.conversation.ui.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageEntityType
import org.monogram.domain.repository.StickerRepository
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.features.stickers.ui.view.StickerImage

@Immutable
data class MessageTextRenderData(
    val annotatedText: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>,
    val isBigEmoji: Boolean,
    val bigEmojiItems: List<BigEmojiItem>
)

@Immutable
sealed interface BigEmojiItem {
    @Immutable
    data class Plain(val emoji: String) : BigEmojiItem

    @Immutable
    data class Custom(val path: String?) : BigEmojiItem
}

@Composable
private fun rememberResolvedCustomEmojiPaths(
    entities: List<MessageEntity>,
    stickerRepository: StickerRepository = koinInject()
): List<String?> {
    val emojiEntities =
        entities.filter { it.type is MessageEntityType.CustomEmoji }.sortedBy { it.offset }

    return emojiEntities.map { entity ->
        val type = entity.type as MessageEntityType.CustomEmoji
        key(entity.offset, entity.length, type.emojiId, type.path) {
            val resolvedPath by if (type.path == null) {
                stickerRepository.getCustomEmojiFile(type.emojiId).collectAsState(initial = null)
            } else {
                remember(type.path) { androidx.compose.runtime.mutableStateOf(type.path) }
            }
            resolvedPath
        }
    }
}

@Composable
fun rememberMessageInlineContent(
    entities: List<MessageEntity>,
    fontSize: Float,
    isBigEmoji: Boolean = false,
    stickerRepository: StickerRepository = koinInject()
): Map<String, InlineTextContent> {
    val emojiEntities =
        entities.filter { it.type is MessageEntityType.CustomEmoji }.sortedBy { it.offset }
    val resolvedEmojiPaths = rememberResolvedCustomEmojiPaths(entities, stickerRepository)

    return remember(emojiEntities, resolvedEmojiPaths, fontSize, isBigEmoji) {
        val map = mutableMapOf<String, InlineTextContent>()
        val emojiSizeDp = if (isBigEmoji) fontSize * 5f else fontSize * 1.5f
        val emojiSizeSp = emojiSizeDp.sp
        emojiEntities.forEachIndexed { index, entity ->
            map[inlineEmojiContentId(index, entity)] = InlineTextContent(
                Placeholder(emojiSizeSp, emojiSizeSp, PlaceholderVerticalAlign.Center)
            ) {
                StickerImage(
                    path = resolvedEmojiPaths.getOrNull(index),
                    modifier = Modifier.size(emojiSizeDp.dp)
                )
            }
        }
        map
    }
}

@Composable
fun rememberMessageTextRenderData(
    text: String,
    entities: List<MessageEntity>,
    fontSize: Float,
    allowBigEmoji: Boolean = true,
    isOutgoing: Boolean = false,
    revealedSpoilers: List<Int> = emptyList(),
    appPreferences: AppPreferences = koinInject(),
    stickerRepository: StickerRepository = koinInject()
): MessageTextRenderData {
    val bigEmoji = remember(text, entities, allowBigEmoji) {
        allowBigEmoji && isBigEmoji(text, entities)
    }
    val resolvedEmojiPaths = rememberResolvedCustomEmojiPaths(entities, stickerRepository)
    val annotatedText = buildAnnotatedMessageTextWithEmoji(
        text = text,
        entities = entities,
        isOutgoing = isOutgoing,
        revealedSpoilers = revealedSpoilers,
        appPreferences = appPreferences
    )
    val inlineContent = rememberMessageInlineContent(
        entities = entities,
        fontSize = fontSize,
        isBigEmoji = bigEmoji,
        stickerRepository = stickerRepository
    )
    val bigEmojiItems = remember(text, entities, resolvedEmojiPaths, bigEmoji) {
        if (!bigEmoji) emptyList() else buildBigEmojiItems(text, entities, resolvedEmojiPaths)
    }

    return remember(annotatedText, inlineContent, bigEmoji, bigEmojiItems) {
        MessageTextRenderData(
            annotatedText = annotatedText,
            inlineContent = inlineContent,
            isBigEmoji = bigEmoji,
            bigEmojiItems = bigEmojiItems
        )
    }
}

private fun buildBigEmojiItems(
    text: String,
    entities: List<MessageEntity>,
    resolvedEmojiPaths: List<String?>
): List<BigEmojiItem> {
    val emojiEntities =
        entities.filter { it.type is MessageEntityType.CustomEmoji }.sortedBy { it.offset }
    val items = mutableListOf<BigEmojiItem>()
    var currentPos = 0

    fun appendPlainEmojiSegment(segment: String) {
        if (segment.isBlank()) return
        val iterator = java.text.BreakIterator.getCharacterInstance().apply { setText(segment) }
        var start = iterator.first()
        var end = iterator.next()
        while (end != java.text.BreakIterator.DONE) {
            val part = segment.substring(start, end)
            if (part.isNotBlank()) items += BigEmojiItem.Plain(part)
            start = end
            end = iterator.next()
        }
    }

    emojiEntities.forEachIndexed { index, entity ->
        val safeStart = entity.offset.coerceIn(0, text.length)
        val safeEnd = (entity.offset + entity.length).coerceIn(safeStart, text.length)
        if (safeStart > currentPos) {
            appendPlainEmojiSegment(text.substring(currentPos, safeStart))
        }
        items += BigEmojiItem.Custom(resolvedEmojiPaths.getOrNull(index))
        currentPos = maxOf(currentPos, safeEnd)
    }

    if (currentPos < text.length) {
        appendPlainEmojiSegment(text.substring(currentPos))
    }

    return items.filterNot {
        it is BigEmojiItem.Plain && it.emoji.isBlank()
    }.take(3)
}

private fun inlineEmojiContentId(index: Int, entity: MessageEntity): String {
    val type = entity.type as? MessageEntityType.CustomEmoji
    return if (type != null) {
        "emoji_${index}_${entity.offset}_${entity.length}_${type.emojiId}"
    } else {
        "emoji_$index"
    }
}

@Composable
fun BigEmojiContent(
    items: List<BigEmojiItem>,
    sizeDp: Float,
    modifier: Modifier = Modifier,
    appPreferences: AppPreferences = koinInject()
) {
    val context = LocalContext.current
    val emojiStyle by appPreferences.emojiStyle.collectAsState()
    val emojiFontFamily = remember(context, emojiStyle) { getEmojiFontFamily(context, emojiStyle) }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { item ->
            when (item) {
                is BigEmojiItem.Custom -> StickerImage(
                    path = item.path,
                    modifier = Modifier.size(sizeDp.dp)
                )

                is BigEmojiItem.Plain -> androidx.compose.material3.Text(
                    text = item.emoji,
                    fontSize = sizeDp.sp,
                    fontFamily = emojiFontFamily,
                    lineHeight = (sizeDp * 1.05f).sp
                )
            }
        }
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
    val revealedSpoilersSnapshot = revealedSpoilers.toSet()

    return remember(
        text,
        entities,
        isOutgoing,
        revealedSpoilersSnapshot,
        emojiFontFamily,
        linkColor,
        codeBackgroundColor,
        codeTextColor
    ) {
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
                    appendInlineContent(inlineEmojiContentId(index, entity), "[emoji]")
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

            otherEntities.forEach { entity ->
                val safeStart = entity.offset.coerceIn(0, text.length)
                val safeEnd = (entity.offset + entity.length).coerceIn(safeStart, text.length)
                val start = indexMapping[safeStart] ?: return@forEach
                val end = indexMapping[safeEnd] ?: indexMapping[text.length] ?: return@forEach
                if (start >= end) return@forEach

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
                        val spoilerKey = spoilerKeyForEntity(entity)
                        val isRevealed = revealedSpoilersSnapshot.contains(spoilerKey)
                        if (isRevealed) {
                            addStyle(SpanStyle(background = Color.Gray.copy(alpha = 0.2f)), start, end)
                            addStringAnnotation("SPOILER_REVEALED", spoilerKey.toString(), start, end)
                        } else {
                            addStyle(
                                SpanStyle(color = Color.Transparent),
                                start,
                                end
                            )
                            addStringAnnotation("SPOILER_UNREVEALED", spoilerKey.toString(), start, end)
                        }
                        addStringAnnotation("SPOILER", spoilerKey.toString(), start, end)
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

private fun spoilerKeyForEntity(entity: MessageEntity): Int {
    return (entity.offset * 1_000_003) xor entity.length
}

private fun String.safeSubstring(start: Int, end: Int): String {
    if (isEmpty()) return ""
    val safeStart = start.coerceIn(0, length)
    val safeEnd = end.coerceIn(safeStart, length)
    return substring(safeStart, safeEnd)
}
