package org.monogram.presentation.features.chats.conversation.ui.inputbar

import android.content.ClipData
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageEntityType
import org.monogram.domain.models.StickerModel
import org.monogram.domain.repository.EditorSnippet
import org.monogram.domain.repository.EditorSnippetProvider
import org.monogram.domain.repository.FormattedTextResult
import org.monogram.domain.repository.MessageAiRepository
import org.monogram.domain.repository.RichTextParsingRepository
import org.monogram.domain.repository.StickerRepository
import org.monogram.domain.repository.TextCompositionStyleModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsTextField
import org.monogram.presentation.features.chats.conversation.ui.message.BigEmojiContent
import org.monogram.presentation.features.chats.conversation.ui.message.MessageText
import org.monogram.presentation.features.chats.conversation.ui.message.addEmojiStyle
import org.monogram.presentation.features.chats.conversation.ui.message.rememberMessageTextRenderData
import org.monogram.presentation.features.profile.logs.components.calculateDiff
import org.monogram.presentation.features.stickers.ui.menu.StickerEmojiMenu
import org.monogram.presentation.features.stickers.ui.view.StickerImage
import java.util.Locale

internal enum class AiEditorMode {
    Translate,
    Stylize,
    Generate,
    Fix
}

private data class AiLanguageOption(
    val code: String,
    val label: String
)

private val AI_LANGUAGE_CODES = listOf(
    "en",
    "ru",
    "uk",
    "de",
    "fr",
    "es",
    "it",
    "pt",
    "tr",
    "ar",
    "fa",
    "hi",
    "id",
    "ja",
    "ko",
    "pl",
    "nl",
    "sv",
    "cs",
    "ro",
    "vi",
    "zh"
)

private val DEFAULT_AI_STYLES = listOf(
    TextCompositionStyleModel(name = "formal", customEmojiId = 5357080225463149588L, title = "Formal"),
    TextCompositionStyleModel(name = "short", customEmojiId = 5350460637182993292L, title = "Short"),
    TextCompositionStyleModel(name = "tribal", customEmojiId = 5470159421512359552L, title = "Tribal"),
    TextCompositionStyleModel(name = "corp", customEmojiId = 5359785904535774578L, title = "Corp"),
    TextCompositionStyleModel(name = "biblical", customEmojiId = 5350571717922167592L, title = "Biblical"),
    TextCompositionStyleModel(name = "viking", customEmojiId = 5350341795437911403L, title = "Viking"),
    TextCompositionStyleModel(name = "zen", customEmojiId = 5442983582882601962L, title = "Zen")
)

private fun buildAiLanguageOptions(): List<AiLanguageOption> {
    return AI_LANGUAGE_CODES
        .distinct()
        .map { code ->
            val label = Locale(code).getDisplayLanguage(Locale.getDefault()).ifBlank { code }
            AiLanguageOption(
                code = code,
                label = label.replaceFirstChar { char -> char.titlecase(Locale.getDefault()) })
        }
}

@Composable
private fun resolveAiStyleTitle(style: TextCompositionStyleModel): String {
    return when (style.name.lowercase(Locale.ROOT)) {
        "formal" -> stringResource(R.string.editor_ai_style_formal)
        "short" -> stringResource(R.string.editor_ai_style_short)
        "tribal" -> stringResource(R.string.editor_ai_style_tribal)
        "corp" -> stringResource(R.string.editor_ai_style_corp)
        "biblical" -> stringResource(R.string.editor_ai_style_biblical)
        "viking" -> stringResource(R.string.editor_ai_style_viking)
        "zen" -> stringResource(R.string.editor_ai_style_zen)
        else -> style.title.ifBlank { style.name }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FullScreenEditorSheet(
    visible: Boolean,
    textValue: TextFieldValue,
    onTextValueChange: (TextFieldValue) -> Unit,
    canWriteText: Boolean,
    pendingMediaPaths: List<String>,
    knownCustomEmojis: MutableMap<Long, StickerModel>,
    emojiFontFamily: FontFamily,
    isKeyboardVisible: Boolean,
    maxMessageLength: Int,
    initialParseMode: EditorParseMode,
    sendAsRichMessage: Boolean,
    stickerRepository: StickerRepository,
    isPremiumUser: Boolean,
    isSecretChat: Boolean,
    onDismiss: () -> Unit,
    onSend: (TextFieldValue, EditorParseMode) -> Unit,
    onEditorFocus: () -> Unit,
    onDraftAutosave: (String) -> Unit = {}
) {
    if (!visible) return
    val context = LocalContext.current
    val clipboardManager = LocalClipboard.current
    val nativeClipboard = clipboardManager.nativeClipboard

    val focusRequester = remember { FocusRequester() }
    var showEmojiPicker by rememberSaveable { mutableStateOf(false) }
    var showLinkDialog by rememberSaveable { mutableStateOf(false) }
    var linkValue by rememberSaveable { mutableStateOf("https://") }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var languageValue by rememberSaveable { mutableStateOf("") }
    var isPreviewMode by rememberSaveable { mutableStateOf(false) }
    var parseMode by rememberSaveable { mutableStateOf(initialParseMode) }
    var showFindReplace by rememberSaveable { mutableStateOf(false) }
    var findQuery by rememberSaveable { mutableStateOf("") }
    var replaceValue by rememberSaveable { mutableStateOf("") }
    var currentMatchIndex by rememberSaveable { mutableIntStateOf(0) }
    var showTemplatesSheet by rememberSaveable { mutableStateOf(false) }
    var showAutoSaved by remember { mutableStateOf(false) }
    var fontScale by remember { mutableFloatStateOf(1f) }
    var showAiSheet by rememberSaveable { mutableStateOf(false) }
    var aiTranslateLanguage by rememberSaveable { mutableStateOf("") }
    var aiPrompt by rememberSaveable { mutableStateOf("") }
    var aiSelectedStyle by rememberSaveable { mutableStateOf("") }
    var aiAddEmojis by rememberSaveable { mutableStateOf(false) }
    var aiMode by rememberSaveable { mutableStateOf(AiEditorMode.Stylize) }
    var aiShowDiffMode by rememberSaveable { mutableStateOf(true) }
    var aiResultText by remember { mutableStateOf<AnnotatedString?>(null) }
    var aiResultTextValue by remember { mutableStateOf<TextFieldValue?>(null) }
    var aiErrorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var aiLoading by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    val previewRevealedSpoilers = remember { mutableStateListOf<Int>() }

    val snippetProvider: EditorSnippetProvider = koinInject()
    val messageRepository: MessageAiRepository = koinInject()
    val richTextParsingRepository: RichTextParsingRepository = koinInject()
    val supportsPromptBasedAi = messageRepository.supportsPromptBasedAi
    val textCompositionStyles by messageRepository.textCompositionStyles.collectAsState()
    val effectiveAiStyles = remember(textCompositionStyles) {
        if (textCompositionStyles.isEmpty()) DEFAULT_AI_STYLES else textCompositionStyles
    }
    val aiScope = rememberCoroutineScope()
    val canUseAi = isPremiumUser && !isSecretChat
    val userSnippets by snippetProvider.snippets.collectAsState()
    val builtInSnippets = remember {
        listOf(
            EditorSnippet(
                title = "Quick reply",
                text = "Thanks, got it. I will review this and get back to you soon."
            ),
            EditorSnippet(
                title = "Status update",
                text = "Update: task is in progress, current status is stable, next checkpoint in 30 min."
            ),
            EditorSnippet(
                title = "Release note",
                text = "Release notes:\n- Added improvements\n- Fixed edge cases\n- Improved performance"
            )
        )
    }
    val allSnippets = remember(userSnippets, builtInSnippets) { builtInSnippets + userSnippets }

    val undoStack = remember { mutableStateListOf<TextFieldValue>() }
    val redoStack = remember { mutableStateListOf<TextFieldValue>() }

    val matches = remember(textValue.text, findQuery) { findOccurrences(textValue.text, findQuery) }

    val previewPrimaryColor = MaterialTheme.colorScheme.primary
    val previewInlineContent = remember(knownCustomEmojis.size, knownCustomEmojis.hashCode()) {
        val emojiSize = 20.sp
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

    fun previewRawOffsetToDisplayOffset(
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

        emojiEntities.forEach { entity ->
            val safeStart = entity.offset.coerceIn(0, rawText.length)
            val safeEnd = (entity.offset + entity.length).coerceIn(safeStart, rawText.length)

            if (targetOffset <= safeStart) {
                return (displayPosition + (targetOffset - rawPosition)).coerceIn(
                    0,
                    displayTextLength
                )
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

    fun buildMessagePreviewText(
        renderedText: AnnotatedString,
        source: AnnotatedString,
        rawText: String,
        entities: List<MessageEntity>,
        fontSize: Float
    ): AnnotatedString {
        if (source.length == 0) return renderedText

        return buildAnnotatedString {
            append(renderedText)

            source.getStringAnnotations(LATEX_TAG, 0, source.length).forEach { annotation ->
                val start = previewRawOffsetToDisplayOffset(
                    rawText = rawText,
                    entities = entities,
                    rawOffset = annotation.start,
                    displayTextLength = renderedText.length
                )
                val end = previewRawOffsetToDisplayOffset(
                    rawText = rawText,
                    entities = entities,
                    rawOffset = annotation.end,
                    displayTextLength = renderedText.length
                )
                if (start >= end) return@forEach

                val isBlock = annotation.item == "block"
                addStyle(
                    androidx.compose.ui.text.SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        color = previewPrimaryColor,
                        background = previewPrimaryColor.copy(alpha = if (isBlock) 0.16f else 0.12f),
                        fontSize = if (isBlock) (fontSize * 1.08f).sp else androidx.compose.ui.unit.TextUnit.Unspecified
                    ),
                    start,
                    end
                )
                if (isBlock) {
                    addStyle(
                        ParagraphStyle(textAlign = TextAlign.Center),
                        start,
                        end
                    )
                }
            }

            source.getStringAnnotations(EDITOR_HEADING_TAG, 0, source.length)
                .forEach { annotation ->
                    val start = previewRawOffsetToDisplayOffset(
                        rawText = rawText,
                        entities = entities,
                        rawOffset = annotation.start,
                        displayTextLength = renderedText.length
                    )
                    val end = previewRawOffsetToDisplayOffset(
                        rawText = rawText,
                        entities = entities,
                        rawOffset = annotation.end,
                        displayTextLength = renderedText.length
                    )
                    if (start >= end) return@forEach

                    val level = annotation.item.toIntOrNull()?.coerceIn(1, 3) ?: 1
                    addStyle(
                        androidx.compose.ui.text.SpanStyle(
                            fontWeight = FontWeight.Bold,
                            color = previewPrimaryColor,
                            fontSize = when (level) {
                                1 -> (fontSize * 1.48f).sp
                                2 -> (fontSize * 1.28f).sp
                                else -> (fontSize * 1.12f).sp
                            }
                        ),
                        start,
                        end
                    )
                }

            source.getStringAnnotations(EDITOR_DIVIDER_TAG, 0, source.length)
                .forEach { annotation ->
                    val start = previewRawOffsetToDisplayOffset(
                        rawText = rawText,
                        entities = entities,
                        rawOffset = annotation.start,
                        displayTextLength = renderedText.length
                    )
                    val end = previewRawOffsetToDisplayOffset(
                        rawText = rawText,
                        entities = entities,
                        rawOffset = annotation.end,
                        displayTextLength = renderedText.length
                    )
                    if (start >= end) return@forEach

                    addStyle(
                        androidx.compose.ui.text.SpanStyle(
                            color = previewPrimaryColor.copy(alpha = 0.7f)
                        ),
                        start,
                        end
                    )
                    addStyle(
                        ParagraphStyle(textAlign = TextAlign.Center),
                        start,
                        end
                    )
                }
        }
    }

    fun buildPreviewForDisplay(source: AnnotatedString): AnnotatedString {
        val previewAnnotated = buildEditorPreviewAnnotatedString(
            source = source,
            primaryColor = previewPrimaryColor
        )
        val customEmojiAnnotations = source
            .getStringAnnotations(CUSTOM_EMOJI_TAG, 0, source.length)
            .sortedBy { it.start }

        val previewWithCustomEmojis = if (customEmojiAnnotations.isEmpty()) {
            previewAnnotated
        } else {
            buildAnnotatedString {
                var lastIndex = 0

                customEmojiAnnotations.forEach { annotation ->
                    if (annotation.start < lastIndex) return@forEach
                    if (annotation.start > previewAnnotated.length || annotation.end > previewAnnotated.length) return@forEach

                    append(previewAnnotated.subSequence(lastIndex, annotation.start))

                    val stickerId = annotation.item.toLongOrNull()
                    val originalEmoji = previewAnnotated.text.substring(annotation.start, annotation.end)

                    if (stickerId != null && knownCustomEmojis.containsKey(stickerId)) {
                        appendInlineContent(stickerId.toString(), originalEmoji)
                    } else {
                        append(previewAnnotated.subSequence(annotation.start, annotation.end))
                    }

                    lastIndex = annotation.end
                }

                if (lastIndex < previewAnnotated.length) {
                    append(previewAnnotated.subSequence(lastIndex, previewAnnotated.length))
                }
            }
        }

        return if (emojiFontFamily == FontFamily.Default) {
            previewWithCustomEmojis
        } else {
            buildAnnotatedString {
                append(previewWithCustomEmojis)
                addEmojiStyle(previewWithCustomEmojis.text, emojiFontFamily)
            }
        }
    }

    val displayTextValue = remember(textValue, parseMode) {
        applyEditorFormatting(textValue, parseMode)
    }
    LaunchedEffect(displayTextValue.annotatedString) {
        previewRevealedSpoilers.clear()
    }
    val displayMessageLength = displayTextValue.text.length
    val isDisplayOverMessageLimit = displayMessageLength > maxMessageLength
    val wordCount = remember(displayTextValue.text) {
        Regex("\\S+").findAll(displayTextValue.text).count()
    }
    val readingMinutes = remember(wordCount) { ((wordCount + 179) / 180).coerceAtLeast(1) }

    fun applyEditorChange(newValue: TextFieldValue, trackHistory: Boolean = true) {
        if (newValue == textValue) return
        if (trackHistory && newValue.text != textValue.text) {
            if (undoStack.lastOrNull() != textValue) {
                undoStack += textValue
                if (undoStack.size > 60) undoStack.removeAt(0)
            }
            redoStack.clear()
        }
        onTextValueChange(newValue)
    }

    fun focusMatch(targetIndex: Int) {
        if (matches.isEmpty()) return
        val normalized = ((targetIndex % matches.size) + matches.size) % matches.size
        currentMatchIndex = normalized
        val range = matches[normalized]
        applyEditorChange(
            textValue.copy(selection = TextRange(range.first, range.last + 1)),
            trackHistory = false
        )
    }

    val entities = remember(
        displayTextValue.annotatedString,
        knownCustomEmojis.size,
        knownCustomEmojis.hashCode()
    ) {
        extractEntities(displayTextValue.annotatedString, knownCustomEmojis)
    }
    val previewFontSize =
        MaterialTheme.typography.bodyLarge.fontSize.value * fontScale.coerceIn(0.8f, 1.6f)
    val previewCustomEmojiPaths = remember(knownCustomEmojis.size, knownCustomEmojis.hashCode()) {
        knownCustomEmojis.mapValues { (_, sticker) -> sticker.path }
    }
    val previewRenderData = rememberMessageTextRenderData(
        text = displayTextValue.text,
        entities = entities,
        fontSize = previewFontSize,
        isOutgoing = false,
        revealedSpoilers = previewRevealedSpoilers,
        emojiFontFamily = emojiFontFamily,
        customEmojiPaths = previewCustomEmojiPaths
    )
    val previewMessageText = remember(
        previewRenderData.annotatedText,
        displayTextValue.annotatedString,
        displayTextValue.text,
        entities,
        previewFontSize,
        previewPrimaryColor
    ) {
        buildMessagePreviewText(
            renderedText = previewRenderData.annotatedText,
            source = displayTextValue.annotatedString,
            rawText = displayTextValue.text,
            entities = entities,
            fontSize = previewFontSize
        )
    }
    val richEntityCount = remember(entities) { entities.count { richEntityToAnnotation(it.type) != null } }
    val hasSelection = hasFormattableSelection(textValue)
    val hasTextSelection = normalizedSelection(textValue.selection) != null
    val canPasteFromClipboard = canWriteText &&
            nativeClipboard.primaryClip?.let { clip ->
                clip.itemCount > 0 && clip.getItemAt(0).coerceToText(context).isNotEmpty()
            } == true

    fun showAiPreview(result: FormattedTextResult) {
        val mappedTextValue = buildTextFieldValueFromTextAndEntities(
            text = result.text,
            entities = result.entities,
            knownCustomEmojis = knownCustomEmojis
        )
        aiShowDiffMode = true
        aiResultTextValue = mappedTextValue
        aiResultText = buildPreviewForDisplay(mappedTextValue.annotatedString)
    }

    fun runAiRequest(
        requireText: Boolean = true,
        request: suspend () -> FormattedTextResult?
    ) {
        if (requireText && textValue.text.isBlank()) {
            aiErrorMessage = context.getString(R.string.editor_ai_error_empty)
            return
        }
        if (!requireText && aiPrompt.isBlank()) {
            aiErrorMessage = context.getString(R.string.editor_ai_error_empty_prompt)
            return
        }

        aiScope.launch {
            aiLoading = true
            aiErrorMessage = null

            runCatching { request() }
                .onSuccess { result ->
                    if (result != null) {
                        showAiPreview(result)
                    } else {
                        aiErrorMessage = context.getString(R.string.editor_ai_error_generic)
                    }
                }
                .onFailure { throwable ->
                    val message = throwable.message.orEmpty()
                    aiErrorMessage = if (message.contains("AICOMPOSE_FLOOD_PREMIUM", ignoreCase = true)) {
                        context.getString(R.string.editor_ai_error_flood)
                    } else {
                        context.getString(R.string.editor_ai_error_generic)
                    }
                }

            aiLoading = false
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    LaunchedEffect(visible) {
        if (visible) {
            undoStack.clear()
            redoStack.clear()
            showAutoSaved = false
            parseMode = initialParseMode
            aiErrorMessage = null
            aiShowDiffMode = true
            aiResultText = null
            aiResultTextValue = null
            aiLoading = false
        }
    }

    LaunchedEffect(findQuery, matches.size) {
        if (matches.isEmpty()) {
            currentMatchIndex = 0
        } else if (currentMatchIndex >= matches.size) {
            currentMatchIndex = matches.lastIndex
        }
    }

    LaunchedEffect(textValue.text, visible) {
        if (!visible || textValue.text.isBlank()) return@LaunchedEffect
        delay(900)
        onDraftAutosave(textValue.text)
        showAutoSaved = true
        delay(1000)
        showAutoSaved = false
    }

    val onSendClick: () -> Unit = send@{
        if (isSending) return@send

        fun sendResolvedValue(outgoingValue: TextFieldValue) {
            if (outgoingValue != textValue) {
                onTextValueChange(outgoingValue)
            }
            onSend(outgoingValue, parseMode)
        }

        sendResolvedValue(textValue)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        FullScreenEditorSystemBars()
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(16.dp)
                    .imePadding()
            ) {
                FullScreenEditorHeader(
                    isOverLimit = isDisplayOverMessageLimit,
                    isSending = isSending,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
                    onDismiss = onDismiss,
                    onUndo = {
                        if (undoStack.isNotEmpty()) {
                            val previous = undoStack.removeAt(undoStack.lastIndex)
                            if (redoStack.lastOrNull() != textValue) redoStack += textValue
                            onTextValueChange(previous)
                        }
                    },
                    onRedo = {
                        if (redoStack.isNotEmpty()) {
                            val next = redoStack.removeAt(redoStack.lastIndex)
                            if (undoStack.lastOrNull() != textValue) undoStack += textValue
                            onTextValueChange(next)
                        }
                    },
                    onSend = onSendClick
                )
                FullScreenEditorTopActions(
                    isPreviewMode = isPreviewMode,
                    parseMode = parseMode,
                    showFindReplace = showFindReplace,
                    fontScale = fontScale,
                    showAiAction = canUseAi,
                    onTogglePreview = { isPreviewMode = !isPreviewMode },
                    onParseModeChange = { parseMode = it },
                    onToggleFindReplace = { showFindReplace = !showFindReplace },
                    onTemplatesClick = { showTemplatesSheet = true },
                    onAiClick = {
                        showAiSheet = true
                        aiShowDiffMode = true
                        aiResultText = null
                        aiResultTextValue = null
                        aiErrorMessage = null
                    },
                    onZoomOut = { fontScale = (fontScale - 0.1f).coerceAtLeast(0.8f) },
                    onZoomIn = { fontScale = (fontScale + 0.1f).coerceAtMost(1.6f) }
                )

                AnimatedVisibility(visible = showFindReplace) {
                    FullScreenEditorFindReplaceBar(
                        query = findQuery,
                        replacement = replaceValue,
                        matchesCount = matches.size,
                        currentMatchIndex = currentMatchIndex,
                        onQueryChange = {
                            findQuery = it
                            currentMatchIndex = 0
                        },
                        onReplacementChange = { replaceValue = it },
                        onPrev = { focusMatch(currentMatchIndex - 1) },
                        onNext = { focusMatch(currentMatchIndex + 1) },
                        onReplace = {
                            if (matches.isNotEmpty()) {
                                val currentRange = matches[currentMatchIndex]
                                applyEditorChange(
                                    applyReplaceAtRange(textValue, currentRange, replaceValue)
                                )
                            }
                        },
                        onReplaceAll = {
                            applyEditorChange(applyReplaceAll(textValue, findQuery, replaceValue))
                        },
                        onClose = { showFindReplace = false }
                    )
                }

                Spacer(modifier = Modifier.height(if (showFindReplace) 14.dp else 10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FullScreenEditorMetaPill(
                        text = stringResource(
                            R.string.message_length_counter,
                            displayMessageLength,
                            maxMessageLength
                        ),
                        color = if (isDisplayOverMessageLimit) MaterialTheme.colorScheme.error.copy(
                            alpha = 0.22f
                        ) else MaterialTheme.colorScheme.primary.copy(
                            alpha = 0.2f
                        ),
                        contentColor = if (isDisplayOverMessageLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    FullScreenEditorMetaPill(
                        text = stringResource(R.string.fullscreen_editor_blocks, richEntityCount),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                    FullScreenEditorMetaPill(
                        text = stringResource(R.string.editor_word_count, wordCount),
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                    FullScreenEditorMetaPill(
                        text = stringResource(R.string.editor_reading_time, readingMinutes),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    if (isPreviewMode) {
                        val previewTextStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = if (previewRenderData.isBigEmoji) (previewFontSize * 5f).sp else previewFontSize.sp,
                            lineHeight = if (previewRenderData.isBigEmoji) (previewFontSize * 5.5f).sp else (previewFontSize * 1.1f).sp
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            if (previewRenderData.isBigEmoji && previewRenderData.bigEmojiItems.isNotEmpty()) {
                                BigEmojiContent(
                                    items = previewRenderData.bigEmojiItems,
                                    sizeDp = previewFontSize * 5f,
                                    emojiFontFamily = emojiFontFamily
                                )
                            } else {
                                MessageText(
                                    text = previewMessageText,
                                    rawText = displayTextValue.text,
                                    inlineContent = previewRenderData.inlineContent,
                                    entities = entities,
                                    style = previewTextStyle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    onSpoilerClick = { spoilerKey ->
                                        if (previewRevealedSpoilers.contains(spoilerKey)) {
                                            previewRevealedSpoilers.remove(spoilerKey)
                                        } else {
                                            previewRevealedSpoilers.add(spoilerKey)
                                        }
                                    }
                                )
                            }
                        }
                    } else {
                        InputTextField(
                            textValue = textValue,
                            onValueChange = {
                                applyEditorChange(
                                    mergeInputTextValuePreservingAnnotations(textValue, it)
                                )
                            },
                            onRichTextValueChange = { applyEditorChange(it) },
                            enableContextMenu = false,
                            enableRichContextActions = false,
                            canWriteText = canWriteText,
                            knownCustomEmojis = knownCustomEmojis,
                            emojiFontFamily = emojiFontFamily,
                            focusRequester = focusRequester,
                            pendingMediaPaths = pendingMediaPaths,
                            pendingDocumentPaths = emptyList(),
                            fontScale = fontScale,
                            maxEditorHeight = 860.dp,
                            onFocus = onEditorFocus,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                AnimatedVisibility(visible = !isPreviewMode && parseMode == EditorParseMode.Plain) {
                    FullScreenEditorTools(
                        hasSelection = hasSelection,
                        canCopy = hasTextSelection,
                        canCut = canWriteText && hasTextSelection,
                        canPaste = canPasteFromClipboard,
                        onCopy = {
                            selectedTextOrNull(textValue)?.let { selectedText ->
                                nativeClipboard.setPrimaryClip(ClipData.newPlainText("", selectedText))
                            }
                        },
                        onCut = {
                            selectedTextOrNull(textValue)?.let { selectedText ->
                                nativeClipboard.setPrimaryClip(ClipData.newPlainText("", selectedText))
                                applyEditorChange(replaceSelection(textValue, ""))
                            }
                        },
                        onPaste = {
                            val clipboardText =
                                nativeClipboard.primaryClip?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)
                                    ?.coerceToText(context)
                                    ?.toString()
                                    .orEmpty()
                            if (clipboardText.isNotEmpty()) {
                                applyEditorChange(replaceSelection(textValue, clipboardText))
                            }
                        },
                        onBold = { applyEditorChange(toggleRichEntity(textValue, MessageEntityType.Bold)) },
                        onItalic = { applyEditorChange(toggleRichEntity(textValue, MessageEntityType.Italic)) },
                        onUnderline = { applyEditorChange(toggleRichEntity(textValue, MessageEntityType.Underline)) },
                        onStrike = { applyEditorChange(toggleRichEntity(textValue, MessageEntityType.Strikethrough)) },
                        onSpoiler = { applyEditorChange(toggleRichEntity(textValue, MessageEntityType.Spoiler)) },
                        onCode = { applyEditorChange(toggleRichEntity(textValue, MessageEntityType.Code)) },
                        onLink = {
                            linkValue = currentTextUrl(textValue) ?: "https://"
                            showLinkDialog = true
                        },
                        onMention = { applyEditorChange(insertMentionAtSelection(textValue)) },
                        onPre = {
                            languageValue = currentPreLanguage(textValue)
                            showLanguageDialog = true
                        },
                        onClear = { applyEditorChange(clearRichFormatting(textValue)) },
                        onEmoji = { showEmojiPicker = true }
                    )
                }
                AnimatedVisibility(visible = !isPreviewMode && parseMode != EditorParseMode.Plain) {
                    FullScreenEditorMarkupTools(
                        mode = parseMode,
                        canCopy = hasTextSelection,
                        canCut = canWriteText && hasTextSelection,
                        canPaste = canPasteFromClipboard,
                        onCopy = {
                            selectedTextOrNull(textValue)?.let { selectedText ->
                                nativeClipboard.setPrimaryClip(
                                    ClipData.newPlainText(
                                        "",
                                        selectedText
                                    )
                                )
                            }
                        },
                        onCut = {
                            selectedTextOrNull(textValue)?.let { selectedText ->
                                nativeClipboard.setPrimaryClip(
                                    ClipData.newPlainText(
                                        "",
                                        selectedText
                                    )
                                )
                                applyEditorChange(replaceSelection(textValue, ""))
                            }
                        },
                        onPaste = {
                            val clipboardText =
                                nativeClipboard.primaryClip?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)
                                    ?.coerceToText(context)
                                    ?.toString()
                                    .orEmpty()
                            if (clipboardText.isNotEmpty()) {
                                applyEditorChange(replaceSelection(textValue, clipboardText))
                            }
                        },
                        onBold = {
                            applyEditorChange(
                                when (parseMode) {
                                    EditorParseMode.Markdown -> wrapSelectionWith(
                                        textValue,
                                        "**",
                                        "**"
                                    )

                                    EditorParseMode.Html -> wrapSelectionWith(
                                        textValue,
                                        "<b>",
                                        "</b>"
                                    )

                                    EditorParseMode.Plain -> textValue
                                }
                            )
                        },
                        onItalic = {
                            applyEditorChange(
                                when (parseMode) {
                                    EditorParseMode.Markdown -> wrapSelectionWith(
                                        textValue,
                                        "_",
                                        "_"
                                    )

                                    EditorParseMode.Html -> wrapSelectionWith(
                                        textValue,
                                        "<i>",
                                        "</i>"
                                    )

                                    EditorParseMode.Plain -> textValue
                                }
                            )
                        },
                        onUnderline = {
                            applyEditorChange(
                                when (parseMode) {
                                    EditorParseMode.Markdown -> wrapSelectionWith(
                                        textValue,
                                        "__",
                                        "__"
                                    )

                                    EditorParseMode.Html -> wrapSelectionWith(
                                        textValue,
                                        "<u>",
                                        "</u>"
                                    )

                                    EditorParseMode.Plain -> textValue
                                }
                            )
                        },
                        onStrike = {
                            applyEditorChange(
                                when (parseMode) {
                                    EditorParseMode.Markdown -> wrapSelectionWith(
                                        textValue,
                                        "~~",
                                        "~~"
                                    )

                                    EditorParseMode.Html -> wrapSelectionWith(
                                        textValue,
                                        "<s>",
                                        "</s>"
                                    )

                                    EditorParseMode.Plain -> textValue
                                }
                            )
                        },
                        onSpoiler = {
                            applyEditorChange(
                                when (parseMode) {
                                    EditorParseMode.Markdown -> wrapSelectionWith(
                                        textValue,
                                        "||",
                                        "||"
                                    )

                                    EditorParseMode.Html -> wrapSelectionWith(
                                        textValue,
                                        "<tg-spoiler>",
                                        "</tg-spoiler>"
                                    )

                                    EditorParseMode.Plain -> textValue
                                }
                            )
                        },
                        onCode = {
                            applyEditorChange(
                                when (parseMode) {
                                    EditorParseMode.Markdown -> wrapSelectionWith(
                                        textValue,
                                        "`",
                                        "`"
                                    )

                                    EditorParseMode.Html -> wrapSelectionWith(
                                        textValue,
                                        "<code>",
                                        "</code>"
                                    )

                                    EditorParseMode.Plain -> textValue
                                }
                            )
                        },
                        onLink = {
                            linkValue = currentTextUrl(textValue) ?: "https://"
                            showLinkDialog = true
                        },
                        onQuote = {
                            applyEditorChange(
                                when (parseMode) {
                                    EditorParseMode.Markdown -> prefixSelectionLines(
                                        textValue,
                                        "> "
                                    )

                                    EditorParseMode.Html -> wrapSelectionWith(
                                        textValue,
                                        "<blockquote>",
                                        "</blockquote>"
                                    )

                                    EditorParseMode.Plain -> textValue
                                }
                            )
                        },
                        onPre = {
                            languageValue = ""
                            showLanguageDialog = true
                        },
                        onLatex = {
                            applyEditorChange(
                                when (parseMode) {
                                    EditorParseMode.Markdown -> wrapSelectionWith(
                                        textValue,
                                        "$",
                                        "$",
                                        "x^2 + y^2 = z^2"
                                    )

                                    EditorParseMode.Html -> wrapSelectionWith(
                                        textValue,
                                        "<tg-math>",
                                        "</tg-math>",
                                        "x^2 + y^2 = z^2"
                                    )

                                    EditorParseMode.Plain -> textValue
                                }
                            )
                        },
                        onBlockLatex = {
                            applyEditorChange(
                                when (parseMode) {
                                    EditorParseMode.Markdown -> replaceSelection(
                                        textValue,
                                        "\n$$\nx^2 + y^2 = z^2\n$$\n"
                                    )

                                    EditorParseMode.Html -> replaceSelection(
                                        textValue,
                                        "\n<tg-math>x^2 + y^2 = z^2</tg-math>\n"
                                    )

                                    EditorParseMode.Plain -> textValue
                                }
                            )
                        },
                        onHeading1 = {
                            applyEditorChange(applyMarkupHeading(textValue, parseMode, 1))
                        },
                        onHeading2 = {
                            applyEditorChange(applyMarkupHeading(textValue, parseMode, 2))
                        },
                        onHeading3 = {
                            applyEditorChange(applyMarkupHeading(textValue, parseMode, 3))
                        },
                        onBulletList = {
                            applyEditorChange(applyMarkupBulletList(textValue, parseMode))
                        },
                        onNumberedList = {
                            applyEditorChange(applyMarkupNumberedList(textValue, parseMode))
                        },
                        onDivider = {
                            applyEditorChange(applyMarkupDivider(textValue, parseMode))
                        },
                        onTable = {
                            applyEditorChange(applyMarkupTable(textValue, parseMode))
                        }
                    )
                }
                AnimatedVisibility(visible = showAutoSaved) {
                    Text(
                        text = stringResource(R.string.editor_autosave_done),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }
                AnimatedVisibility(visible = !isKeyboardVisible) {
                    Text(
                        text = stringResource(R.string.fullscreen_editor_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }
            }
        }
    }

    if (showLinkDialog) {
        AlertDialog(
            onDismissRequest = { showLinkDialog = false },
            title = { Text(text = stringResource(R.string.rich_text_link_title)) },
            text = {
                SettingsTextField(
                    value = linkValue,
                    onValueChange = { linkValue = it },
                    placeholder = stringResource(R.string.rich_text_link_hint),
                    icon = Icons.Outlined.Link,
                    position = ItemPosition.STANDALONE,
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    normalizeEditorUrl(linkValue)?.let {
                        applyEditorChange(
                            when (parseMode) {
                                EditorParseMode.Plain -> applyTextUrlEntity(textValue, it)
                                EditorParseMode.Markdown -> applyMarkdownLink(textValue, it)
                                EditorParseMode.Html -> applyHtmlLink(textValue, it)
                            }
                        )
                    }
                    showLinkDialog = false
                }) { Text(text = stringResource(R.string.action_apply)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showLinkDialog = false
                }) { Text(text = stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(text = stringResource(R.string.rich_text_code_language_title)) },
            text = {
                SettingsTextField(
                    value = languageValue,
                    onValueChange = { languageValue = it },
                    placeholder = stringResource(R.string.rich_text_code_language_hint),
                    icon = Icons.Outlined.Code,
                    position = ItemPosition.STANDALONE,
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    applyEditorChange(
                        when (parseMode) {
                            EditorParseMode.Plain -> applyPreEntity(textValue, languageValue)
                            EditorParseMode.Markdown -> applyMarkdownPre(textValue, languageValue)
                            EditorParseMode.Html -> applyHtmlPre(textValue, languageValue)
                        }
                    )
                    showLanguageDialog = false
                }) { Text(text = stringResource(R.string.action_apply)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showLanguageDialog = false
                }) { Text(text = stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (showEmojiPicker) {
        ModalBottomSheet(
            onDismissRequest = { showEmojiPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            StickerEmojiMenu(
                onStickerSelected = {},
                onEmojiSelected = { emoji, sticker ->
                    applyEditorChange(insertEmojiAtSelection(textValue, emoji, sticker, knownCustomEmojis))
                },
                onGifSelected = {},
                emojiOnlyMode = true,
                onSearchFocused = {},
                stickerRepository = stickerRepository
            )
        }
    }

    if (showAiSheet) {
        FullScreenEditorAiSheetContent(
            visible = showAiSheet,
            mode = aiMode,
            loading = aiLoading,
            errorMessage = aiErrorMessage,
            styles = effectiveAiStyles,
            stickerRepository = stickerRepository,
            selectedStyleName = aiSelectedStyle,
            translateLanguage = aiTranslateLanguage,
            prompt = aiPrompt,
            addEmojis = aiAddEmojis,
            emojiFontFamily = emojiFontFamily,
            inlineContent = previewInlineContent,
            originalText = textValue.text,
            resultText = aiResultText,
            resultPlainText = aiResultTextValue?.text,
            showDiffMode = aiShowDiffMode,
            supportsPromptBasedAi = supportsPromptBasedAi,
            onDismiss = {
                showAiSheet = false
                aiErrorMessage = null
                aiShowDiffMode = true
                aiResultText = null
                aiResultTextValue = null
            },
            onModeChange = {
                aiMode = it
                aiErrorMessage = null
                aiShowDiffMode = true
                aiResultText = null
                aiResultTextValue = null
            },
            onToggleDiffMode = { aiShowDiffMode = !aiShowDiffMode },
            onStyleSelected = {
                aiSelectedStyle = it
                aiErrorMessage = null
                aiShowDiffMode = true
                aiResultText = null
                aiResultTextValue = null
            },
            onTranslateLanguageChange = {
                aiTranslateLanguage = it
                aiErrorMessage = null
                aiShowDiffMode = true
                aiResultText = null
                aiResultTextValue = null
            },
            onPromptChange = {
                aiPrompt = it
                aiErrorMessage = null
                aiShowDiffMode = true
                aiResultText = null
                aiResultTextValue = null
            },
            onAddEmojisChange = {
                aiAddEmojis = it
                aiErrorMessage = null
                aiShowDiffMode = true
                aiResultText = null
                aiResultTextValue = null
            },
            onRun = {
                when (aiMode) {
                    AiEditorMode.Translate -> runAiRequest {
                        val selectedLanguageCode = aiTranslateLanguage.ifBlank { Locale.getDefault().language }
                        messageRepository.composeTextWithAi(
                            text = textValue.text,
                            entities = entities,
                            translateToLanguageCode = selectedLanguageCode,
                            styleName = "",
                            addEmojis = false
                        )
                    }

                    AiEditorMode.Stylize -> runAiRequest {
                        val selectedStyle = aiSelectedStyle.ifBlank { effectiveAiStyles.firstOrNull()?.name.orEmpty() }
                        messageRepository.composeTextWithAi(
                            text = textValue.text,
                            entities = entities,
                            translateToLanguageCode = "",
                            styleName = selectedStyle,
                            addEmojis = aiAddEmojis
                        )
                    }

                    AiEditorMode.Generate -> runAiRequest(requireText = false) {
                        messageRepository.generateTextWithAi(
                            prompt = aiPrompt.trim(),
                            languageCode = aiTranslateLanguage.ifBlank { Locale.getDefault().language },
                            addEmojis = aiAddEmojis
                        )
                    }

                    AiEditorMode.Fix -> runAiRequest {
                        messageRepository.fixTextWithAi(
                            text = textValue.text,
                            entities = entities
                        )?.let { fixed ->
                            FormattedTextResult(text = fixed.text, entities = fixed.entities)
                        }
                    }
                }
            },
            onApplyResult = {
                aiResultTextValue?.let { applyEditorChange(it) }
                showAiSheet = false
                aiErrorMessage = null
                aiShowDiffMode = true
                aiResultText = null
                aiResultTextValue = null
            }
        )
    }

    FullScreenEditorTemplatesSheet(
        visible = showTemplatesSheet,
        currentText = textValue.text,
        snippets = allSnippets,
        onDismiss = { showTemplatesSheet = false },
        onInsertSnippet = { snippetText ->
            applyEditorChange(insertSnippetAtSelection(textValue, snippetText))
            showTemplatesSheet = false
        },
        onSaveCurrentAsSnippet = { title ->
            val snippet = EditorSnippet(title = title, text = textValue.text)
            val updatedSnippets = (userSnippets + snippet).distinctBy { it.title + it.text }
            snippetProvider.save(updatedSnippets)
        },
        onDeleteSnippet = { snippet ->
            snippetProvider.save(userSnippets - snippet)
        }
    )
}

@Composable
private fun FullScreenEditorSystemBars() {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    val backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    val useDarkIcons = backgroundColor.luminance() > 0.5f

    DisposableEffect(window, useDarkIcons) {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        val previousLightStatus = insetsController.isAppearanceLightStatusBars
        val previousLightNavigation = insetsController.isAppearanceLightNavigationBars

        insetsController.isAppearanceLightStatusBars = useDarkIcons
        insetsController.isAppearanceLightNavigationBars = useDarkIcons

        onDispose {
            insetsController.isAppearanceLightStatusBars = previousLightStatus
            insetsController.isAppearanceLightNavigationBars = previousLightNavigation
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullScreenEditorAiSheet(
    visible: Boolean,
    mode: AiEditorMode,
    loading: Boolean,
    errorMessage: String?,
    styles: List<TextCompositionStyleModel>,
    stickerRepository: StickerRepository,
    selectedStyleName: String,
    translateLanguage: String,
    addEmojis: Boolean,
    emojiFontFamily: FontFamily,
    inlineContent: Map<String, InlineTextContent>,
    originalText: String,
    resultText: AnnotatedString?,
    resultPlainText: String?,
    showDiffMode: Boolean,
    onDismiss: () -> Unit,
    onModeChange: (AiEditorMode) -> Unit,
    onToggleDiffMode: () -> Unit,
    onStyleSelected: (String) -> Unit,
    onTranslateLanguageChange: (String) -> Unit,
    onAddEmojisChange: (Boolean) -> Unit,
    onRun: () -> Unit,
    onApplyResult: () -> Unit
) {
    if (!visible) return

    val modeTabs = buildList {
        add(AiEditorMode.Translate to stringResource(R.string.editor_ai_tab_translate))
        if (styles.isNotEmpty()) {
            add(AiEditorMode.Stylize to stringResource(R.string.editor_ai_tab_stylize))
        }
        add(AiEditorMode.Fix to stringResource(R.string.editor_ai_tab_fix))
    }
    val runButtonText = when (mode) {
        AiEditorMode.Translate -> stringResource(R.string.editor_ai_run_translate)
        AiEditorMode.Stylize -> stringResource(R.string.editor_ai_run_stylize)
        AiEditorMode.Generate -> stringResource(R.string.editor_ai_run_generate)
        AiEditorMode.Fix -> stringResource(R.string.editor_ai_run_fix)
    }
    val sectionTitleStyle = MaterialTheme.typography.labelLarge
    val addedDiffColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.8f)
    val removedDiffColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
    val diffText = remember(originalText, resultPlainText, addedDiffColor, removedDiffColor) {
        if (resultPlainText.isNullOrBlank()) {
            null
        } else {
            calculateDiff(
                old = originalText,
                new = resultPlainText,
                addedColor = addedDiffColor,
                removedColor = removedDiffColor
            )
        }
    }
    val formattedOriginalText = remember(originalText, emojiFontFamily) {
        buildAnnotatedString {
            append(originalText)
            if (emojiFontFamily != FontFamily.Default) {
                addEmojiStyle(originalText, emojiFontFamily)
            }
        }
    }
    val formattedDiffText = remember(diffText, emojiFontFamily) {
        diffText?.let { annotated ->
            if (emojiFontFamily == FontFamily.Default) {
                annotated
            } else {
                buildAnnotatedString {
                    append(annotated)
                    addEmojiStyle(annotated.text, emojiFontFamily)
                }
            }
        }
    }
    val formattedResultText = remember(resultText, emojiFontFamily) {
        resultText?.let { annotated ->
            if (emojiFontFamily == FontFamily.Default) {
                annotated
            } else {
                buildAnnotatedString {
                    append(annotated)
                    addEmojiStyle(annotated.text, emojiFontFamily)
                }
            }
        }
    }
    val actionButtonText = if (resultText != null) stringResource(R.string.editor_ai_apply_result) else runButtonText
    val languageOptions = remember { buildAiLanguageOptions() }
    val fallbackLanguageCode = LocalLocale.current.platformLocale.language.ifBlank { "en" }
    val selectedLanguageCode = translateLanguage.ifBlank { fallbackLanguageCode }
    val selectedLanguage = languageOptions.firstOrNull { it.code == selectedLanguageCode }
    var languageMenuExpanded by remember { mutableStateOf(false) }
    val customEmojiStickerSets by stickerRepository.customEmojiStickerSets.collectAsState()
    val styleEmojiFileIds = remember(customEmojiStickerSets) {
        buildMap<Long, Long> {
            customEmojiStickerSets.forEach { set ->
                set.stickers.forEach { sticker ->
                    val customEmojiId = sticker.customEmojiId
                    if (customEmojiId != null && customEmojiId != 0L) {
                        put(customEmojiId, sticker.id)
                    }
                }
            }
        }
    }

    LaunchedEffect(styles) {
        if (styles.any { it.customEmojiId != 0L }) {
            runCatching { stickerRepository.loadCustomEmojiStickerSets() }
        }
    }

    LaunchedEffect(mode, styles, selectedStyleName) {
        if (styles.isEmpty() && mode == AiEditorMode.Stylize) {
            onModeChange(AiEditorMode.Translate)
            return@LaunchedEffect
        }

        if (mode == AiEditorMode.Stylize && styles.isNotEmpty()) {
            val hasSelected = styles.any { it.name == selectedStyleName }
            if (!hasSelected) {
                onStyleSelected(styles.first().name)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.editor_ai_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.action_close)
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .selectableGroup()
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            RoundedCornerShape(50)
                        )
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    modeTabs.forEach { (tabMode, label) ->
                        val selected = mode == tabMode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(50))
                                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .selectable(
                                    selected = selected,
                                    onClick = { if (!loading) onModeChange(tabMode) },
                                    role = Role.RadioButton,
                                    enabled = !loading
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                AnimatedContent(targetState = mode, label = "ai_mode_content") { currentMode ->
                    when (currentMode) {
                        AiEditorMode.Translate -> {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.editor_ai_target_language),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    ExposedDropdownMenuBox(
                                        expanded = languageMenuExpanded,
                                        onExpandedChange = { expanded ->
                                            if (!loading) {
                                                languageMenuExpanded = expanded
                                            }
                                        }
                                    ) {
                                        OutlinedTextField(
                                            value = selectedLanguage?.label ?: selectedLanguageCode,
                                            onValueChange = {},
                                            readOnly = true,
                                            singleLine = true,
                                            enabled = !loading,
                                            placeholder = { Text(stringResource(R.string.editor_ai_select_language)) },
                                            leadingIcon = {
                                                Icon(Icons.Outlined.Translate, contentDescription = null)
                                            },
                                            trailingIcon = {
                                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageMenuExpanded)
                                            },
                                            shape = RoundedCornerShape(16.dp),
                                            modifier = Modifier
                                                .menuAnchor(
                                                    type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                                                    enabled = !loading
                                                )
                                                .fillMaxWidth()
                                        )
                                        ExposedDropdownMenu(
                                            expanded = languageMenuExpanded,
                                            onDismissRequest = { languageMenuExpanded = false }
                                        ) {
                                            languageOptions.forEach { option ->
                                                DropdownMenuItem(
                                                    text = { Text(option.label) },
                                                    onClick = {
                                                        onTranslateLanguageChange(option.code)
                                                        languageMenuExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        AiEditorMode.Stylize -> {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    if (styles.isNotEmpty()) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            styles.forEach { style ->
                                                AiStyleChip(
                                                    selected = style.name == selectedStyleName,
                                                    onClick = { onStyleSelected(style.name) },
                                                    label = resolveAiStyleTitle(style),
                                                    emojiFileId = styleEmojiFileIds[style.customEmojiId],
                                                    enabled = !loading,
                                                    stickerRepository = stickerRepository
                                                )
                                            }
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.editor_ai_add_emojis),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Switch(
                                            checked = addEmojis,
                                            onCheckedChange = onAddEmojisChange,
                                            enabled = !loading
                                        )
                                    }
                                }
                            }
                        }

                        AiEditorMode.Generate -> Unit
                        AiEditorMode.Fix -> Unit
                    }
                }

                AnimatedContent(targetState = resultText != null, label = "ai_result_visibility") { hasResult ->
                    if (!hasResult) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.editor_ai_original),
                                    style = sectionTitleStyle,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = formattedOriginalText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    } else {
                        AnimatedContent(targetState = showDiffMode, label = "ai_result_view_mode") { isDiffMode ->
                            if (isDiffMode && formattedDiffText != null) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 300.dp),
                                    shape = RoundedCornerShape(24.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .verticalScroll(rememberScrollState())
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = stringResource(R.string.editor_ai_changes),
                                                style = sectionTitleStyle,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.weight(1f)
                                            )
                                            IconButton(
                                                onClick = onToggleDiffMode,
                                                modifier = Modifier.size(40.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.VisibilityOff,
                                                    contentDescription = stringResource(R.string.editor_ai_changes),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                        Text(
                                            text = formattedDiffText,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(24.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainer
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.editor_ai_original),
                                                style = sectionTitleStyle,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = formattedOriginalText,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 6,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 300.dp),
                                        shape = RoundedCornerShape(24.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainer
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .verticalScroll(rememberScrollState())
                                                .padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.editor_ai_result),
                                                    style = sectionTitleStyle,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                IconButton(
                                                    onClick = onToggleDiffMode,
                                                    modifier = Modifier.size(40.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Outlined.Visibility,
                                                        contentDescription = stringResource(R.string.editor_ai_changes),
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                            Text(
                                                text = formattedResultText ?: AnnotatedString(""),
                                                inlineContent = inlineContent,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = !errorMessage.isNullOrBlank(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Text(
                        text = errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                AnimatedVisibility(
                    visible = loading,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = stringResource(R.string.editor_ai_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = {
                    if (resultText != null) onApplyResult() else onRun()
                },
                enabled = !loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                AnimatedContent(targetState = actionButtonText, label = "ai_action_text") { title ->
                    Text(text = title, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AiStyleChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    emojiFileId: Long?,
    enabled: Boolean,
    stickerRepository: StickerRepository
) {
    val emojiPath by if (emojiFileId != null) {
        stickerRepository.getStickerFile(emojiFileId).collectAsState(initial = null)
    } else {
        remember(emojiFileId) { mutableStateOf(null) }
    }

    val backgroundColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = backgroundColor,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (emojiFileId != null) {
                if (emojiPath != null) {
                    StickerImage(
                        path = emojiPath,
                        modifier = Modifier.size(18.dp),
                        animate = true
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            )
        }
    }
}

