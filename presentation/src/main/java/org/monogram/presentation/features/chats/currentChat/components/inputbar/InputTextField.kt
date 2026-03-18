package org.monogram.presentation.features.chats.currentChat.components.inputbar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageEntityType
import org.monogram.domain.models.StickerModel
import org.monogram.presentation.R
import org.monogram.presentation.features.chats.currentChat.components.chats.addEmojiStyle
import org.monogram.presentation.features.stickers.ui.view.StickerImage

internal const val CUSTOM_EMOJI_TAG = "custom_emoji"
internal const val MENTION_TAG = "mention"

@Composable
fun InputTextField(
    textValue: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    canWriteText: Boolean,
    knownCustomEmojis: Map<Long, StickerModel>,
    emojiFontFamily: FontFamily,
    focusRequester: FocusRequester,
    pendingMediaPaths: List<String>,
    onFocus: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val emojiSize = 20.sp
    val inlineContent = remember(knownCustomEmojis.size) {
        knownCustomEmojis.map { (id, sticker) ->
            id.toString() to InlineTextContent(
                Placeholder(emojiSize, emojiSize, PlaceholderVerticalAlign.Center)
            ) {
                StickerImage(
                    path = sticker.path,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }.toMap()
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val transformedTextState = remember(textValue.annotatedString, knownCustomEmojis, emojiFontFamily, primaryColor) {
        val text = textValue.annotatedString
        val emojiAnnotations = text.getStringAnnotations(CUSTOM_EMOJI_TAG, 0, text.length)
        val mentionAnnotations = text.getStringAnnotations(MENTION_TAG, 0, text.length)

        val builder = AnnotatedString.Builder()
        var lastIndex = 0
        val sortedEmojiAnnotations = emojiAnnotations.sortedBy { it.start }

        for (annotation in sortedEmojiAnnotations) {
            if (annotation.start < lastIndex) continue
            builder.append(text.subSequence(lastIndex, annotation.start))
            val stickerId = annotation.item.toLongOrNull()
            val originalEmoji = text.substring(annotation.start, annotation.end)
            if (stickerId != null && knownCustomEmojis.containsKey(stickerId)) {
                builder.appendInlineContent(stickerId.toString(), originalEmoji)
            } else {
                builder.append(originalEmoji)
            }
            lastIndex = annotation.end
        }
        if (lastIndex < text.length) builder.append(text.subSequence(lastIndex, text.length))

        val result = builder.toAnnotatedString()
        val finalBuilder = AnnotatedString.Builder(result)

        // Add emoji style
        finalBuilder.addEmojiStyle(result.text, emojiFontFamily)

        // Add mention highlighting
        mentionAnnotations.forEach { annotation ->
            finalBuilder.addStyle(SpanStyle(color = primaryColor), annotation.start, annotation.end)
        }

        // Highlight @username style mentions that are not yet annotated
        val mentionRegex = Regex("@(\\w+)")
        mentionRegex.findAll(result.text).forEach { match ->
            if (mentionAnnotations.none { it.start <= match.range.first && it.end >= match.range.last + 1 }) {
                finalBuilder.addStyle(SpanStyle(color = primaryColor), match.range.first, match.range.last + 1)
            }
        }

        TransformedText(finalBuilder.toAnnotatedString(), OffsetMapping.Identity)
    }

    val hasCustomEmojis = knownCustomEmojis.isNotEmpty() &&
            textValue.annotatedString.getStringAnnotations(CUSTOM_EMOJI_TAG, 0, textValue.text.length).isNotEmpty()

    val scrollState = rememberScrollState()

    LaunchedEffect(textValue.text) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    Box(
        modifier = modifier
            .padding(vertical = 10.dp)
            .heightIn(max = 140.dp)
            .verticalScroll(scrollState),
        contentAlignment = Alignment.CenterStart
    ) {
        if (canWriteText) {
            BasicTextField(
                value = textValue,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { if (it.isFocused) onFocus() },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = if (hasCustomEmojis || emojiFontFamily != FontFamily.Default) Color.Transparent else MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                minLines = 1,
                maxLines = Int.MAX_VALUE,
                visualTransformation = { transformedTextState },
                decorationBox = { innerTextField ->
                    Box {
                        if (hasCustomEmojis || emojiFontFamily != FontFamily.Default || textValue.text.contains('@')) {
                            Text(
                                text = transformedTextState.text,
                                inlineContent = inlineContent,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (textValue.text.isEmpty()) {
                            Text(
                                text = if (pendingMediaPaths.isNotEmpty()) stringResource(R.string.input_placeholder_caption) else stringResource(R.string.input_placeholder_message),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        innerTextField()
                    }
                }
            )
        } else {
            Text(
                text = stringResource(R.string.input_error_not_allowed),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp)
            )
        }
    }
}

fun extractEntities(text: AnnotatedString, knownCustomEmojis: Map<Long, StickerModel>): List<MessageEntity> {
    val entities = mutableListOf<MessageEntity>()

    // Custom Emojis
    text.getStringAnnotations(CUSTOM_EMOJI_TAG, 0, text.length).forEach { annotation ->
        val stickerId = annotation.item.toLongOrNull()
        if (stickerId != null) {
            val sticker = knownCustomEmojis[stickerId]
            entities.add(
                MessageEntity(
                    offset = annotation.start,
                    length = annotation.end - annotation.start,
                    type = MessageEntityType.CustomEmoji(stickerId, sticker?.path)
                )
            )
        }
    }

    // Text Mentions (for users without username)
    text.getStringAnnotations(MENTION_TAG, 0, text.length).forEach { annotation ->
        val userId = annotation.item.toLongOrNull()
        if (userId != null) {
            entities.add(
                MessageEntity(
                    offset = annotation.start,
                    length = annotation.end - annotation.start,
                    type = MessageEntityType.TextMention(userId)
                )
            )
        }
    }

    // Regular Mentions
    val mentionRegex = Regex("@(\\w+)")
    mentionRegex.findAll(text.text).forEach { match ->
        if (entities.none { it.offset <= match.range.first && it.offset + it.length >= match.range.last + 1 }) {
            entities.add(
                MessageEntity(
                    offset = match.range.first,
                    length = match.range.last - match.range.first + 1,
                    type = MessageEntityType.Mention
                )
            )
        }
    }

    return entities
}