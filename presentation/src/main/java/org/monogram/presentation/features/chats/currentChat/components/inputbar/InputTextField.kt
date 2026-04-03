package org.monogram.presentation.features.chats.currentChat.components.inputbar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
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
internal const val RICH_ENTITY_TAG = "rich_entity"

private const val ENTITY_BOLD = "bold"
private const val ENTITY_ITALIC = "italic"
private const val ENTITY_UNDERLINE = "underline"
private const val ENTITY_STRIKE = "strikethrough"
private const val ENTITY_SPOILER = "spoiler"
private const val ENTITY_CODE = "code"
private const val ENTITY_PRE = "pre"
private const val ENTITY_TEXT_URL = "text_url"

private object RichMenuActionBold
private object RichMenuActionItalic
private object RichMenuActionUnderline
private object RichMenuActionStrike
private object RichMenuActionSpoiler
private object RichMenuActionCode
private object RichMenuActionPre
private object RichMenuActionLink
private object RichMenuActionClear

private data class Interval(val start: Int, val end: Int)

@Composable
fun InputTextField(
    textValue: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onRichTextValueChange: (TextFieldValue) -> Unit = onValueChange,
    enableContextMenu: Boolean = true,
    enableRichContextActions: Boolean = true,
    canWriteText: Boolean,
    knownCustomEmojis: Map<Long, StickerModel>,
    emojiFontFamily: FontFamily,
    focusRequester: FocusRequester,
    pendingMediaPaths: List<String>,
    maxEditorHeight: Dp = 140.dp,
    onFocus: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showLinkDialog by remember { mutableStateOf(false) }
    var linkValue by remember { mutableStateOf("https://") }
    var showPreLanguageDialog by remember { mutableStateOf(false) }
    var preLanguageValue by remember { mutableStateOf("") }

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

        text.getStringAnnotations(RICH_ENTITY_TAG, 0, text.length).forEach { annotation ->
            val style = decodeRichEntity(annotation.item)?.toEditorStyle(primaryColor)
            if (style != null && annotation.start < annotation.end && annotation.end <= result.length) {
                finalBuilder.addStyle(style, annotation.start, annotation.end)
            }
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
    val hasRichFormatting = textValue.annotatedString
        .getStringAnnotations(RICH_ENTITY_TAG, 0, textValue.text.length)
        .isNotEmpty()
    val shouldUseOverlayText =
        hasCustomEmojis || emojiFontFamily != FontFamily.Default || textValue.text.contains('@') || hasRichFormatting

    val scrollState = rememberScrollState()

    LaunchedEffect(textValue.text) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    val richTextBold = stringResource(R.string.rich_text_bold)
    val richTextItalic = stringResource(R.string.rich_text_italic)
    val richTextUnderline = stringResource(R.string.rich_text_underline)
    val richTextStrike = stringResource(R.string.rich_text_strikethrough)
    val richTextSpoiler = stringResource(R.string.rich_text_spoiler)
    val richTextCode = stringResource(R.string.rich_text_code)
    val richTextPre = stringResource(R.string.rich_text_pre)
    val richTextLink = stringResource(R.string.rich_text_link)
    val richTextClear = stringResource(R.string.rich_text_clear)

    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .padding(vertical = 10.dp)
                .heightIn(max = maxEditorHeight)
                .verticalScroll(scrollState),
            contentAlignment = Alignment.CenterStart
        ) {
            if (canWriteText) {
                val fieldModifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { if (it.isFocused) onFocus() }
                    .let { base ->
                        when {
                            !enableContextMenu -> base.filterTextContextMenuComponents { false }
                            enableRichContextActions -> {
                                base
                                    .filterTextContextMenuComponents { component ->
                                        when (component.key) {
                                            TextContextMenuKeys.CutKey,
                                            TextContextMenuKeys.CopyKey,
                                            TextContextMenuKeys.PasteKey,
                                            TextContextMenuKeys.SelectAllKey,
                                            RichMenuActionBold,
                                            RichMenuActionItalic,
                                            RichMenuActionUnderline,
                                            RichMenuActionStrike,
                                            RichMenuActionSpoiler,
                                            RichMenuActionCode,
                                            RichMenuActionPre,
                                            RichMenuActionLink,
                                            RichMenuActionClear -> true

                                            else -> false
                                        }
                                    }
                                    .appendTextContextMenuComponents {
                                        if (hasFormattableSelection(textValue)) {
                                            separator()
                                            item(RichMenuActionBold, richTextBold) {
                                                onRichTextValueChange(
                                                    toggleRichEntity(
                                                        textValue,
                                                        MessageEntityType.Bold
                                                    )
                                                )
                                                close()
                                            }
                                            item(RichMenuActionItalic, richTextItalic) {
                                                onRichTextValueChange(
                                                    toggleRichEntity(
                                                        textValue,
                                                        MessageEntityType.Italic
                                                    )
                                                )
                                                close()
                                            }
                                            item(RichMenuActionUnderline, richTextUnderline) {
                                                onRichTextValueChange(
                                                    toggleRichEntity(
                                                        textValue,
                                                        MessageEntityType.Underline
                                                    )
                                                )
                                                close()
                                            }
                                            item(RichMenuActionStrike, richTextStrike) {
                                                onRichTextValueChange(
                                                    toggleRichEntity(
                                                        textValue,
                                                        MessageEntityType.Strikethrough
                                                    )
                                                )
                                                close()
                                            }
                                            item(RichMenuActionSpoiler, richTextSpoiler) {
                                                onRichTextValueChange(
                                                    toggleRichEntity(
                                                        textValue,
                                                        MessageEntityType.Spoiler
                                                    )
                                                )
                                                close()
                                            }
                                            item(RichMenuActionCode, richTextCode) {
                                                onRichTextValueChange(
                                                    toggleRichEntity(
                                                        textValue,
                                                        MessageEntityType.Code
                                                    )
                                                )
                                                close()
                                            }
                                            item(RichMenuActionPre, richTextPre) {
                                                val selection = textValue.selection.normalized()
                                                val current = textValue.annotatedString
                                                    .getStringAnnotations(
                                                        RICH_ENTITY_TAG,
                                                        selection.start,
                                                        selection.end
                                                    )
                                                    .firstOrNull { decodeRichEntity(it.item) is MessageEntityType.Pre }
                                                preLanguageValue =
                                                    (current?.let { decodeRichEntity(it.item) } as? MessageEntityType.Pre)?.language.orEmpty()
                                                showPreLanguageDialog = true
                                                close()
                                            }
                                            item(RichMenuActionLink, richTextLink) {
                                                val selection = textValue.selection.normalized()
                                                val current = textValue.annotatedString
                                                    .getStringAnnotations(
                                                        RICH_ENTITY_TAG,
                                                        selection.start,
                                                        selection.end
                                                    )
                                                    .firstOrNull { decodeRichEntity(it.item) is MessageEntityType.TextUrl }
                                                val existingUrl =
                                                    (current?.let { decodeRichEntity(it.item) } as? MessageEntityType.TextUrl)?.url
                                                linkValue = existingUrl ?: "https://"
                                                showLinkDialog = true
                                                close()
                                            }
                                            item(RichMenuActionClear, richTextClear) {
                                                onRichTextValueChange(clearRichFormatting(textValue))
                                                close()
                                            }
                                        }
                                    }
                            }

                            else -> base
                        }
                    }

                val textStyle = MaterialTheme.typography.bodyLarge.copy(
                    platformStyle = PlatformTextStyle(
                        includeFontPadding = false
                    ),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.None
                    )
                )

                BasicTextField(
                    value = textValue,
                    onValueChange = onValueChange,
                    modifier = fieldModifier,
                    textStyle = textStyle.copy(
                        color = if (shouldUseOverlayText) Color.Transparent else MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    minLines = 1,
                    maxLines = Int.MAX_VALUE,
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (textValue.text.isEmpty()) {
                                Text(
                                    text = if (pendingMediaPaths.isNotEmpty())
                                        stringResource(R.string.input_placeholder_caption)
                                    else
                                        stringResource(R.string.input_placeholder_message),
                                    style = textStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            if (shouldUseOverlayText) {
                                Text(
                                    text = transformedTextState.text,
                                    style = textStyle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.fillMaxWidth()
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

        if (showLinkDialog) {
            AlertDialog(
                onDismissRequest = { showLinkDialog = false },
                title = { Text(text = stringResource(R.string.rich_text_link_title)) },
                text = {
                    OutlinedTextField(
                        value = linkValue,
                        onValueChange = { linkValue = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(text = stringResource(R.string.rich_text_link_hint)) }
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val normalizedUrl = normalizeUrl(linkValue)
                            if (normalizedUrl != null && !textValue.selection.collapsed) {
                                onRichTextValueChange(
                                    applyRichEntity(
                                        textValue,
                                        MessageEntityType.TextUrl(normalizedUrl),
                                        toggle = false
                                    )
                                )
                            }
                            showLinkDialog = false
                        }
                    ) {
                        Text(text = stringResource(R.string.action_apply))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLinkDialog = false }) {
                        Text(text = stringResource(R.string.action_cancel))
                    }
                }
            )
        }

        if (showPreLanguageDialog) {
            AlertDialog(
                onDismissRequest = { showPreLanguageDialog = false },
                title = { Text(text = stringResource(R.string.rich_text_code_language_title)) },
                text = {
                    OutlinedTextField(
                        value = preLanguageValue,
                        onValueChange = { preLanguageValue = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(text = stringResource(R.string.rich_text_code_language_hint)) }
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onRichTextValueChange(applyPreEntity(textValue, preLanguageValue))
                            showPreLanguageDialog = false
                        }
                    ) {
                        Text(text = stringResource(R.string.action_apply))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPreLanguageDialog = false }) {
                        Text(text = stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }
}

internal fun mergeInputTextValuePreservingAnnotations(
    currentValue: TextFieldValue,
    incomingValue: TextFieldValue
): TextFieldValue {
    val trackedTags = setOf(RICH_ENTITY_TAG, CUSTOM_EMOJI_TAG, MENTION_TAG)
    val currentTracked = collectTrackedAnnotations(currentValue.annotatedString, trackedTags)
    val incomingTracked = collectTrackedAnnotations(incomingValue.annotatedString, trackedTags)

    if (currentValue.text != incomingValue.text) {
        val incomingCount = incomingTracked.values.sumOf { it.size }
        if (incomingCount > 0) return incomingValue

        val transformedTracked = currentTracked.mapValues { (_, ranges) ->
            transformRangesByTextDiff(
                oldText = currentValue.text,
                newText = incomingValue.text,
                ranges = ranges
            )
        }

        return incomingValue.copy(
            annotatedString = rebuildWithTrackedAnnotations(
                base = incomingValue.annotatedString,
                trackedTags = trackedTags,
                trackedSource = transformedTracked
            )
        )
    }

    if (currentTracked == incomingTracked) return incomingValue

    val currentCount = currentTracked.values.sumOf { it.size }
    val incomingCount = incomingTracked.values.sumOf { it.size }
    val shouldAcceptIncoming = incomingCount >= currentCount

    if (shouldAcceptIncoming) return incomingValue

    return incomingValue.copy(
        annotatedString = rebuildWithTrackedAnnotations(
            base = incomingValue.annotatedString,
            trackedTags = trackedTags,
            trackedSource = currentTracked
        )
    )
}

private fun transformRangesByTextDiff(
    oldText: String,
    newText: String,
    ranges: List<AnnotatedString.Range<String>>
): List<AnnotatedString.Range<String>> {
    if (ranges.isEmpty()) return emptyList()

    val prefix = longestCommonPrefix(oldText, newText)
    val oldRemain = oldText.length - prefix
    val newRemain = newText.length - prefix
    val suffix = longestCommonSuffix(
        oldText = oldText,
        newText = newText,
        oldLimit = oldRemain,
        newLimit = newRemain
    )

    val oldChangedStart = prefix
    val oldChangedEnd = oldText.length - suffix
    val delta = (newText.length - oldText.length)

    return ranges.mapNotNull { range ->
        val start = range.start
        val end = range.end

        val shifted = when {
            end <= oldChangedStart -> range.copy()
            start >= oldChangedEnd -> range.copy(start = start + delta, end = end + delta)
            else -> null
        }

        shifted?.takeIf { it.start < it.end && it.end <= newText.length }
    }
}

private fun longestCommonPrefix(a: String, b: String): Int {
    val max = minOf(a.length, b.length)
    var i = 0
    while (i < max && a[i] == b[i]) i++
    return i
}

private fun longestCommonSuffix(oldText: String, newText: String, oldLimit: Int, newLimit: Int): Int {
    val max = minOf(oldLimit, newLimit)
    var i = 0
    while (i < max && oldText[oldText.length - 1 - i] == newText[newText.length - 1 - i]) i++
    return i
}

private fun collectTrackedAnnotations(
    text: AnnotatedString,
    trackedTags: Set<String>
): Map<String, List<AnnotatedString.Range<String>>> {
    return trackedTags.associateWith { tag ->
        text.getStringAnnotations(tag, 0, text.length)
    }
}

private fun rebuildWithTrackedAnnotations(
    base: AnnotatedString,
    trackedTags: Set<String>,
    trackedSource: Map<String, List<AnnotatedString.Range<String>>>
): AnnotatedString {
    val builder = AnnotatedString.Builder(base.text)
    base.getStringAnnotations(0, base.length)
        .filter { it.tag !in trackedTags }
        .forEach { annotation ->
            builder.addStringAnnotation(annotation.tag, annotation.item, annotation.start, annotation.end)
        }

    trackedTags.forEach { tag ->
        trackedSource[tag].orEmpty().forEach { annotation ->
            if (annotation.start < annotation.end && annotation.end <= base.length) {
                builder.addStringAnnotation(annotation.tag, annotation.item, annotation.start, annotation.end)
            }
        }
    }

    return builder.toAnnotatedString()
}

fun extractEntities(text: AnnotatedString, knownCustomEmojis: Map<Long, StickerModel>): List<MessageEntity> {
    val entities = mutableListOf<MessageEntity>()

    text.getStringAnnotations(RICH_ENTITY_TAG, 0, text.length).forEach { annotation ->
        val type = decodeRichEntity(annotation.item) ?: return@forEach
        if (annotation.start < annotation.end) {
            entities.add(
                MessageEntity(
                    offset = annotation.start,
                    length = annotation.end - annotation.start,
                    type = type
                )
            )
        }
    }

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
        .sortedWith(compareBy<MessageEntity> { it.offset }.thenByDescending { it.length })
        .distinctBy { Triple(it.offset, it.length, it.type) }
}

internal fun richEntityToAnnotation(type: MessageEntityType): String? {
    return when (type) {
        is MessageEntityType.Bold -> ENTITY_BOLD
        is MessageEntityType.Italic -> ENTITY_ITALIC
        is MessageEntityType.Underline -> ENTITY_UNDERLINE
        is MessageEntityType.Strikethrough -> ENTITY_STRIKE
        is MessageEntityType.Spoiler -> ENTITY_SPOILER
        is MessageEntityType.Code -> ENTITY_CODE
        is MessageEntityType.Pre -> if (type.language.isBlank()) ENTITY_PRE else "$ENTITY_PRE:${type.language}"
        is MessageEntityType.TextUrl -> "$ENTITY_TEXT_URL:${type.url}"
        else -> null
    }
}

internal fun decodeRichEntity(value: String): MessageEntityType? {
    return when {
        value == ENTITY_BOLD -> MessageEntityType.Bold
        value == ENTITY_ITALIC -> MessageEntityType.Italic
        value == ENTITY_UNDERLINE -> MessageEntityType.Underline
        value == ENTITY_STRIKE -> MessageEntityType.Strikethrough
        value == ENTITY_SPOILER -> MessageEntityType.Spoiler
        value == ENTITY_CODE -> MessageEntityType.Code
        value == ENTITY_PRE -> MessageEntityType.Pre()
        value.startsWith("$ENTITY_PRE:") -> MessageEntityType.Pre(value.substringAfter(':'))
        value.startsWith("$ENTITY_TEXT_URL:") -> MessageEntityType.TextUrl(value.substringAfter(':'))
        else -> null
    }
}

private fun MessageEntityType.toEditorStyle(primaryColor: Color): SpanStyle? {
    val codeBackground = primaryColor.copy(alpha = 0.14f)
    val spoilerBackground = primaryColor.copy(alpha = 0.25f)
    return when (this) {
        is MessageEntityType.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
        is MessageEntityType.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
        is MessageEntityType.Underline -> SpanStyle(textDecoration = TextDecoration.Underline)
        is MessageEntityType.Strikethrough -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        is MessageEntityType.Spoiler -> SpanStyle(background = spoilerBackground)
        is MessageEntityType.Code -> SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
        is MessageEntityType.Pre -> SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
        is MessageEntityType.TextUrl -> SpanStyle(color = primaryColor, textDecoration = TextDecoration.Underline)
        else -> null
    }
}

internal fun toggleRichEntity(textValue: TextFieldValue, type: MessageEntityType): TextFieldValue {
    return applyRichEntity(textValue, type, toggle = true)
}

private fun applyRichEntity(textValue: TextFieldValue, type: MessageEntityType, toggle: Boolean): TextFieldValue {
    val selection = textValue.selection.normalized()
    if (selection.collapsed) return textValue

    val applicableSelectionRanges = getFormattableSelectionRanges(textValue, selection)
    if (applicableSelectionRanges.isEmpty()) return textValue

    val targetKey = richEntityToAnnotation(type) ?: return textValue
    val oldText = textValue.annotatedString
    val richAnnotations = oldText.getStringAnnotations(RICH_ENTITY_TAG, 0, oldText.length)

    val sameTypeIntervals = richAnnotations
        .filter { it.item == targetKey }
        .map { Interval(it.start, it.end) }

    val fullyCovered = if (toggle) {
        applicableSelectionRanges.all { range ->
            isSelectionFullyCovered(
                selection = TextRange(range.start, range.end),
                intervals = sameTypeIntervals
            )
        }
    } else {
        false
    }

    val builder = AnnotatedString.Builder(oldText.text)

    richAnnotations.forEach { annotation ->
        val start = annotation.start
        val end = annotation.end
        if (start >= end) return@forEach

        if (annotation.item == targetKey && overlapsAny(start, end, applicableSelectionRanges)) {
            subtractRanges(Interval(start, end), applicableSelectionRanges).forEach { segment ->
                builder.addStringAnnotation(RICH_ENTITY_TAG, annotation.item, segment.start, segment.end)
            }
        } else {
            builder.addStringAnnotation(RICH_ENTITY_TAG, annotation.item, start, end)
        }
    }

    oldText.getStringAnnotations(0, oldText.length)
        .filter { it.tag != RICH_ENTITY_TAG }
        .forEach { annotation ->
            builder.addStringAnnotation(annotation.tag, annotation.item, annotation.start, annotation.end)
        }

    if (!fullyCovered) {
        applicableSelectionRanges.forEach { range ->
            builder.addStringAnnotation(RICH_ENTITY_TAG, targetKey, range.start, range.end)
        }
    }

    return textValue.copy(annotatedString = builder.toAnnotatedString(), selection = selection)
}

internal fun clearRichFormatting(textValue: TextFieldValue): TextFieldValue {
    val selection = textValue.selection.normalized()
    if (selection.collapsed) return textValue

    val oldText = textValue.annotatedString
    val builder = AnnotatedString.Builder(oldText.text)

    oldText.getStringAnnotations(RICH_ENTITY_TAG, 0, oldText.length).forEach { annotation ->
        val start = annotation.start
        val end = annotation.end
        if (start >= end) return@forEach
        if (!overlaps(start, end, selection.start, selection.end)) {
            builder.addStringAnnotation(RICH_ENTITY_TAG, annotation.item, start, end)
        } else {
            if (start < selection.start) {
                builder.addStringAnnotation(RICH_ENTITY_TAG, annotation.item, start, selection.start)
            }
            if (end > selection.end) {
                builder.addStringAnnotation(RICH_ENTITY_TAG, annotation.item, selection.end, end)
            }
        }
    }

    oldText.getStringAnnotations(0, oldText.length)
        .filter { it.tag != RICH_ENTITY_TAG }
        .forEach { annotation ->
            builder.addStringAnnotation(annotation.tag, annotation.item, annotation.start, annotation.end)
        }

    return textValue.copy(annotatedString = builder.toAnnotatedString(), selection = selection)
}

internal fun applyTextUrlEntity(textValue: TextFieldValue, url: String): TextFieldValue {
    return applyRichEntity(textValue, MessageEntityType.TextUrl(url), toggle = false)
}

internal fun applyPreEntity(textValue: TextFieldValue, language: String): TextFieldValue {
    val normalizedLanguage = language.trim()
    val entityType =
        if (normalizedLanguage.isEmpty()) MessageEntityType.Pre() else MessageEntityType.Pre(normalizedLanguage)
    return applyRichEntity(textValue, entityType, toggle = false)
}

internal fun hasFormattableSelection(textValue: TextFieldValue): Boolean {
    val selection = textValue.selection.normalized()
    if (selection.collapsed) return false
    return getFormattableSelectionRanges(textValue, selection).isNotEmpty()
}

private fun getFormattableSelectionRanges(textValue: TextFieldValue, selection: TextRange): List<Interval> {
    val normalized = selection.normalized()
    if (normalized.collapsed) return emptyList()

    val blocked = mutableListOf<Interval>()

    textValue.annotatedString.getStringAnnotations(CUSTOM_EMOJI_TAG, 0, textValue.annotatedString.length)
        .forEach { annotation ->
            if (annotation.start < annotation.end) {
                blocked += Interval(annotation.start, annotation.end)
            }
        }

    blocked += detectEmojiIntervals(textValue.text)

    return subtractRanges(Interval(normalized.start, normalized.end), blocked)
}

private fun detectEmojiIntervals(text: String): List<Interval> {
    if (text.isEmpty()) return emptyList()

    val intervals = mutableListOf<Interval>()
    var index = 0
    while (index < text.length) {
        val cp = Character.codePointAt(text, index)
        val cpLength = Character.charCount(cp)
        val nextIndex = index + cpLength
        if (isEmojiCodePoint(cp)) {
            intervals += Interval(index, nextIndex)
        }
        index = nextIndex
    }
    return intervals
}

private fun isEmojiCodePoint(codePoint: Int): Boolean {
    return codePoint in 0x1F1E6..0x1F1FF ||
            codePoint in 0x1F300..0x1FAFF ||
            codePoint in 0x2600..0x27BF ||
            codePoint in 0xFE00..0xFE0F ||
            codePoint in 0x1F3FB..0x1F3FF ||
            codePoint == 0x200D ||
            codePoint == 0x20E3
}

private fun overlapsAny(start: Int, end: Int, ranges: List<Interval>): Boolean {
    return ranges.any { overlaps(start, end, it.start, it.end) }
}

private fun subtractRanges(source: Interval, blockedRanges: List<Interval>): List<Interval> {
    if (source.start >= source.end) return emptyList()
    if (blockedRanges.isEmpty()) return listOf(source)

    val relevant = blockedRanges
        .filter { overlaps(source.start, source.end, it.start, it.end) }
        .sortedBy { it.start }

    if (relevant.isEmpty()) return listOf(source)

    var cursor = source.start
    val result = mutableListOf<Interval>()

    relevant.forEach { blocked ->
        val cutStart = blocked.start.coerceIn(source.start, source.end)
        val cutEnd = blocked.end.coerceIn(source.start, source.end)
        if (cutStart > cursor) {
            result += Interval(cursor, cutStart)
        }
        cursor = maxOf(cursor, cutEnd)
    }

    if (cursor < source.end) {
        result += Interval(cursor, source.end)
    }

    return result
}

private fun isSelectionFullyCovered(selection: TextRange, intervals: List<Interval>): Boolean {
    if (selection.collapsed) return false
    if (intervals.isEmpty()) return false

    val merged = intervals
        .filter { it.end > selection.start && it.start < selection.end }
        .sortedBy { it.start }
        .fold(mutableListOf<Interval>()) { acc, interval ->
            if (acc.isEmpty()) {
                acc += interval
            } else {
                val last = acc.last()
                if (interval.start <= last.end) {
                    acc[acc.lastIndex] = Interval(last.start, maxOf(last.end, interval.end))
                } else {
                    acc += interval
                }
            }
            acc
        }

    var cursor = selection.start
    merged.forEach { interval ->
        if (interval.start > cursor) return false
        cursor = maxOf(cursor, interval.end)
        if (cursor >= selection.end) return true
    }
    return cursor >= selection.end
}

private fun overlaps(startA: Int, endA: Int, startB: Int, endB: Int): Boolean {
    return startA < endB && endA > startB
}

private fun TextRange.normalized(): TextRange {
    return if (start <= end) this else TextRange(end, start)
}

private fun normalizeUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return if (trimmed.contains("://")) trimmed else "https://$trimmed"
}
