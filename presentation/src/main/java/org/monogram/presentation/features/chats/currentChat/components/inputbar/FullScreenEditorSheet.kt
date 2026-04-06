package org.monogram.presentation.features.chats.currentChat.components.inputbar

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Subject
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.*
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
import org.monogram.domain.models.MessageEntityType
import org.monogram.domain.models.StickerModel
import org.monogram.domain.repository.*
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.features.chats.chatList.components.SettingsTextField
import org.monogram.presentation.features.chats.currentChat.components.chats.addEmojiStyle
import org.monogram.presentation.features.profile.logs.components.calculateDiff
import org.monogram.presentation.features.stickers.ui.menu.StickerEmojiMenu
import org.monogram.presentation.features.stickers.ui.view.StickerImage
import java.util.*

private enum class AiEditorMode {
    Translate,
    Stylize,
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
fun FullScreenEditorSheet(
    visible: Boolean,
    textValue: TextFieldValue,
    onTextValueChange: (TextFieldValue) -> Unit,
    canWriteText: Boolean,
    pendingMediaPaths: List<String>,
    knownCustomEmojis: MutableMap<Long, StickerModel>,
    emojiFontFamily: FontFamily,
    isKeyboardVisible: Boolean,
    isOverMessageLimit: Boolean,
    currentMessageLength: Int,
    maxMessageLength: Int,
    stickerRepository: StickerRepository,
    isPremiumUser: Boolean,
    isSecretChat: Boolean,
    onDismiss: () -> Unit,
    onSend: () -> Unit,
    onEditorFocus: () -> Unit,
    onDraftAutosave: (String) -> Unit = {}
) {
    if (!visible) return
    val context = LocalContext.current

    val focusRequester = remember { FocusRequester() }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }
    var linkValue by remember { mutableStateOf("https://") }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var languageValue by remember { mutableStateOf("") }
    var isPreviewMode by remember { mutableStateOf(false) }
    var markdownMode by remember { mutableStateOf(false) }
    var showFindReplace by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var replaceValue by remember { mutableStateOf("") }
    var currentMatchIndex by remember { mutableIntStateOf(0) }
    var showTemplatesSheet by remember { mutableStateOf(false) }
    var showAutoSaved by remember { mutableStateOf(false) }
    var fontScale by remember { mutableFloatStateOf(1f) }
    var showAiSheet by remember { mutableStateOf(false) }
    var aiTranslateLanguage by remember { mutableStateOf("") }
    var aiSelectedStyle by remember { mutableStateOf("") }
    var aiAddEmojis by remember { mutableStateOf(false) }
    var aiMode by remember { mutableStateOf(AiEditorMode.Stylize) }
    var aiShowDiffMode by remember { mutableStateOf(true) }
    var aiResultText by remember { mutableStateOf<AnnotatedString?>(null) }
    var aiResultTextValue by remember { mutableStateOf<TextFieldValue?>(null) }
    var aiErrorMessage by remember { mutableStateOf<String?>(null) }
    var aiLoading by remember { mutableStateOf(false) }

    val snippetProvider: EditorSnippetProvider = koinInject()
    val messageRepository: MessageAiRepository = koinInject()
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
    val wordCount = remember(textValue.text) {
        Regex("\\S+").findAll(textValue.text).count()
    }
    val readingMinutes = remember(wordCount) { ((wordCount + 179) / 180).coerceAtLeast(1) }

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

    val previewText = remember(
        textValue.annotatedString,
        knownCustomEmojis.size,
        knownCustomEmojis.hashCode(),
        previewPrimaryColor,
        emojiFontFamily
    ) {
        buildPreviewForDisplay(textValue.annotatedString)
    }

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

    val entities = remember(textValue.annotatedString, knownCustomEmojis.size) {
        extractEntities(textValue.annotatedString, knownCustomEmojis)
    }
    val richEntityCount = remember(entities) { entities.count { richEntityToAnnotation(it.type) != null } }
    val hasSelection = hasFormattableSelection(textValue)

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

    fun runAiRequest(request: suspend () -> FormattedTextResult?) {
        if (textValue.text.isBlank()) {
            aiErrorMessage = context.getString(R.string.editor_ai_error_empty)
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

    val onSendClick: () -> Unit = {
        val outgoingValue = if (markdownMode) applyMarkdownFormatting(textValue) else textValue
        if (outgoingValue != textValue) {
            onTextValueChange(outgoingValue)
        }
        onSend()
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
                FullScreenEditorHeader(isOverMessageLimit, onDismiss, onSendClick)
                FullScreenEditorTopActions(
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
                    isPreviewMode = isPreviewMode,
                    markdownMode = markdownMode,
                    showFindReplace = showFindReplace,
                    fontScale = fontScale,
                    showAiAction = canUseAi,
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
                    onTogglePreview = { isPreviewMode = !isPreviewMode },
                    onToggleMarkdown = { markdownMode = !markdownMode },
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FullScreenEditorMetaPill(
                        text = stringResource(R.string.message_length_counter, currentMessageLength, maxMessageLength),
                        color = if (isOverMessageLimit) MaterialTheme.colorScheme.error.copy(alpha = 0.22f) else MaterialTheme.colorScheme.primary.copy(
                            alpha = 0.2f
                        ),
                        contentColor = if (isOverMessageLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
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
                        Text(
                            text = previewText,
                            inlineContent = previewInlineContent,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = MaterialTheme.typography.bodyLarge.fontSize * fontScale.coerceIn(0.8f, 1.6f)
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        )
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
                AnimatedVisibility(visible = !isPreviewMode) {
                    FullScreenEditorTools(
                        hasSelection = hasSelection,
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
                        applyEditorChange(applyTextUrlEntity(textValue, it))
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
                    applyEditorChange(applyPreEntity(textValue, languageValue))
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
        FullScreenEditorAiSheet(
            visible = showAiSheet,
            mode = aiMode,
            loading = aiLoading,
            errorMessage = aiErrorMessage,
            styles = effectiveAiStyles,
            stickerRepository = stickerRepository,
            selectedStyleName = aiSelectedStyle,
            translateLanguage = aiTranslateLanguage,
            addEmojis = aiAddEmojis,
            emojiFontFamily = emojiFontFamily,
            inlineContent = previewInlineContent,
            originalText = textValue.text,
            resultText = aiResultText,
            resultPlainText = aiResultTextValue?.text,
            showDiffMode = aiShowDiffMode,
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

@Composable
private fun FullScreenEditorHeader(isOverLimit: Boolean, onDismiss: () -> Unit, onSend: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_cancel),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = stringResource(R.string.fullscreen_editor_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onSend, enabled = !isOverLimit) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = stringResource(R.string.action_send),
                tint = if (isOverLimit) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun FullScreenEditorTopActions(
    canUndo: Boolean,
    canRedo: Boolean,
    isPreviewMode: Boolean,
    markdownMode: Boolean,
    showFindReplace: Boolean,
    fontScale: Float,
    showAiAction: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onTogglePreview: () -> Unit,
    onToggleMarkdown: () -> Unit,
    onToggleFindReplace: () -> Unit,
    onTemplatesClick: () -> Unit,
    onAiClick: () -> Unit,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TextButton(onClick = onUndo, enabled = canUndo) {
            Text(text = stringResource(R.string.editor_undo))
        }
        TextButton(onClick = onRedo, enabled = canRedo) {
            Text(text = stringResource(R.string.editor_redo))
        }
        if (showAiAction) {
            TextButton(onClick = onAiClick) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = stringResource(R.string.editor_ai))
            }
        }
        TextButton(onClick = onTogglePreview) {
            Text(
                text = if (isPreviewMode) {
                    stringResource(R.string.editor_mode_edit)
                } else {
                    stringResource(R.string.editor_mode_preview)
                }
            )
        }
        TextButton(onClick = onToggleMarkdown) {
            Text(
                text = if (markdownMode) {
                    stringResource(R.string.editor_markdown_on)
                } else {
                    stringResource(R.string.editor_markdown_off)
                }
            )
        }
        TextButton(onClick = onToggleFindReplace) {
            Text(
                text = if (showFindReplace) {
                    stringResource(R.string.action_close)
                } else {
                    stringResource(R.string.editor_find)
                }
            )
        }
        TextButton(onClick = onTemplatesClick) {
            Text(text = stringResource(R.string.editor_templates))
        }
        TextButton(onClick = onZoomOut, enabled = fontScale > 0.8f) {
            Text(text = stringResource(R.string.editor_zoom_out))
        }
        TextButton(onClick = onZoomIn, enabled = fontScale < 1.6f) {
            Text(text = stringResource(R.string.editor_zoom_in))
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
    val fallbackLanguageCode = Locale.getDefault().language.ifBlank { "en" }
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

@Composable
private fun FullScreenEditorMetaPill(text: String, color: Color, contentColor: Color) {
    Surface(color = color, shape = RoundedCornerShape(999.dp)) {
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun FullScreenEditorToolButton(icon: ImageVector, hint: String, enabled: Boolean = true, onClick: () -> Unit) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = { Toast.makeText(context, hint, Toast.LENGTH_SHORT).show() })
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = hint,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                alpha = 0.38f
            )
        )
    }
}

@Composable
private fun FullScreenEditorTools(
    hasSelection: Boolean,
    onBold: () -> Unit,
    onItalic: () -> Unit,
    onUnderline: () -> Unit,
    onStrike: () -> Unit,
    onSpoiler: () -> Unit,
    onCode: () -> Unit,
    onLink: () -> Unit,
    onMention: () -> Unit,
    onPre: () -> Unit,
    onClear: () -> Unit,
    onEmoji: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier
                .weight(1f)
                .height(58.dp),
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                FullScreenEditorToolButton(
                    Icons.Outlined.FormatBold,
                    stringResource(R.string.rich_text_bold),
                    hasSelection,
                    onBold
                )
                FullScreenEditorToolButton(
                    Icons.Outlined.FormatItalic,
                    stringResource(R.string.rich_text_italic),
                    hasSelection,
                    onItalic
                )
                FullScreenEditorToolButton(
                    Icons.Outlined.FormatUnderlined,
                    stringResource(R.string.rich_text_underline),
                    hasSelection,
                    onUnderline
                )
                FullScreenEditorToolButton(
                    Icons.Outlined.FormatStrikethrough,
                    stringResource(R.string.rich_text_strikethrough),
                    hasSelection,
                    onStrike
                )
                FullScreenEditorToolButton(
                    Icons.Outlined.VisibilityOff,
                    stringResource(R.string.rich_text_spoiler),
                    hasSelection,
                    onSpoiler
                )
                FullScreenEditorToolButton(
                    Icons.Outlined.Code,
                    stringResource(R.string.rich_text_code),
                    hasSelection,
                    onCode
                )
                FullScreenEditorToolButton(
                    Icons.Outlined.Link,
                    stringResource(R.string.rich_text_link),
                    hasSelection,
                    onLink
                )
                FullScreenEditorToolButton(
                    Icons.Outlined.AlternateEmail,
                    stringResource(R.string.rich_text_mention),
                    true,
                    onMention
                )
                FullScreenEditorToolButton(
                    Icons.AutoMirrored.Outlined.Subject,
                    stringResource(R.string.rich_text_pre),
                    hasSelection,
                    onPre
                )
                FullScreenEditorToolButton(
                    Icons.Outlined.FormatClear,
                    stringResource(R.string.rich_text_clear),
                    hasSelection,
                    onClear
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Surface(
            modifier = Modifier.size(58.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            IconButton(onClick = onEmoji) {
                Text(
                    text = "☺",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private fun currentTextUrl(value: TextFieldValue): String? {
    val range = normalizedSelection(value.selection) ?: return null
    return value.annotatedString.getStringAnnotations(RICH_ENTITY_TAG, range.start, range.end)
        .firstOrNull { decodeRichEntity(it.item) is MessageEntityType.TextUrl }
        ?.let { decodeRichEntity(it.item) as? MessageEntityType.TextUrl }
        ?.url
}

private fun currentPreLanguage(value: TextFieldValue): String {
    val range = normalizedSelection(value.selection) ?: return ""
    return value.annotatedString.getStringAnnotations(RICH_ENTITY_TAG, range.start, range.end)
        .firstOrNull { decodeRichEntity(it.item) is MessageEntityType.Pre }
        ?.let { decodeRichEntity(it.item) as? MessageEntityType.Pre }
        ?.language
        .orEmpty()
}

private fun insertSnippetAtSelection(value: TextFieldValue, snippet: String): TextFieldValue {
    if (snippet.isBlank()) return value
    val rawSelection = if (value.selection.start <= value.selection.end) {
        value.selection
    } else {
        TextRange(value.selection.end, value.selection.start)
    }
    val maxLength = value.annotatedString.length
    val selection = TextRange(
        start = rawSelection.start.coerceIn(0, maxLength),
        end = rawSelection.end.coerceIn(0, maxLength)
    )
    val newAnnotated = buildAnnotatedString {
        append(value.annotatedString.subSequence(0, selection.start))
        append(snippet)
        append(value.annotatedString.subSequence(selection.end, value.annotatedString.length))
    }
    val cursor = selection.start + snippet.length
    return value.copy(annotatedString = newAnnotated, selection = TextRange(cursor, cursor))
}

private fun normalizedSelection(selection: TextRange): TextRange? {
    if (selection.start == selection.end) return null
    return if (selection.start <= selection.end) selection else TextRange(selection.end, selection.start)
}

private fun normalizeEditorUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return if (trimmed.contains("://")) trimmed else "https://$trimmed"
}

private fun insertMentionAtSelection(value: TextFieldValue): TextFieldValue {
    val rawSelection = if (value.selection.start <= value.selection.end) value.selection else TextRange(
        value.selection.end,
        value.selection.start
    )
    val maxLength = value.annotatedString.length
    val selection = TextRange(
        start = rawSelection.start.coerceIn(0, maxLength),
        end = rawSelection.end.coerceIn(0, maxLength)
    )
    val insertion =
        if (selection.start == selection.end) "@" else "@${value.text.substring(selection.start, selection.end)}"
    val newAnnotated = buildAnnotatedString {
        append(value.annotatedString.subSequence(0, selection.start))
        append(insertion)
        append(value.annotatedString.subSequence(selection.end, value.annotatedString.length))
    }
    val newCursor = selection.start + insertion.length
    return value.copy(annotatedString = newAnnotated, selection = TextRange(newCursor, newCursor))
}
