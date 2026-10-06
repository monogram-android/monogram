package org.monogram.feature.dialog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatClear
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.monogram.core.models.StyledText
import org.monogram.core.models.TextEntity
import org.monogram.core.models.parseComposerMarkupMapped
import org.monogram.core.models.prepareComposerText
import org.monogram.core.models.toggleComposerEntity
import org.monogram.core.ui.ExpressiveDefaults
import org.monogram.core.ui.components.SettingsChoice
import org.monogram.core.ui.components.SettingsChoiceGroup
import org.monogram.feature.dialog.R

internal fun shouldShowFullScreenEditor(text: String, markdownAvailable: Boolean = true): Boolean {
    val display = collapseCustomEmojiMarkdown(text)?.text?.text ?: text
    if (display.isBlank()) return false
    return display.codePointCount(0, display.length) >= 180 || display.count { it == '\n' } >= 2 ||
            (markdownAvailable && parseComposerMarkupMapped(text).styled.let { it.text != text || it.entities.isNotEmpty() })
}

internal fun needsFullScreenEditor(text: String): Boolean = shouldShowFullScreenEditor(text)

internal fun wrapCodeBlock(value: TextFieldValue, language: String): TextFieldValue {
    val start = value.selection.min
    val end = value.selection.max
    val prefix = if (start > 0 && value.text[start - 1] != '\n') "\n" else ""
    val suffix = if (end < value.text.length && value.text[end] != '\n') "\n" else ""
    return wrapMarkdown(value, "$prefix```$language\n", "\n```$suffix")
}

internal fun finishMarkdownEditor(
    snapshot: TextFieldValue,
    current: TextFieldValue,
    apply: Boolean,
): TextFieldValue = if (apply) current else snapshot

internal fun pushEditorUndo(
    current: TextFieldValue,
    next: TextFieldValue,
    undo: MutableList<TextFieldValue>,
    redo: MutableList<TextFieldValue>,
) {
    if (next.text == current.text) return
    undo += current.copy(composition = null)
    if (undo.size > 60) undo.removeAt(0)
    redo.clear()
}

/**
 * Counts non-whitespace runs. Separators are the ASCII set `" \t\n\u000B\u000C\r"` rather than
 * [Char.isWhitespace], so non-breaking and unicode spaces count as word characters.
 */
internal fun countEditorWords(text: String): Int {
    var count = 0
    var inWord = false
    for (ch in text) {
        if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\u000B' || ch == '\u000C' || ch == '\r') {
            inWord = false
        } else if (!inWord) {
            inWord = true
            count++
        }
    }
    return count
}


@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MarkdownEditorScreen(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    entities: List<TextEntity> = emptyList(),
    onEntitiesChange: (List<TextEntity>) -> Unit = {},
    markdownAvailable: Boolean = false,
    markdownEnabled: Boolean = false,
    onMarkdownEnabled: (Boolean) -> Unit = {},
    preparePreview: suspend (String, List<TextEntity>, Boolean) -> StyledText = { text, explicit, markdown ->
        prepareComposerText(text, markdown, explicit)
    },
    onEmoji: (() -> Unit)? = null,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var previewMode by rememberSaveable { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var linkDialog by remember { mutableStateOf(false) }
    var codeDialog by remember { mutableStateOf(false) }
    var linkUrl by rememberSaveable { mutableStateOf("https://") }
    var language by rememberSaveable { mutableStateOf("") }
    val undo = remember { mutableStateListOf<Pair<TextFieldValue, List<TextEntity>>>() }
    val redo = remember { mutableStateListOf<Pair<TextFieldValue, List<TextEntity>>>() }
    var spoilersRevealed by remember(value.text, entities) { mutableStateOf(false) }
    val selection = value.selection
    val hasSelection = !selection.collapsed
    fun saveUndo() {
        undo += value.copy(composition = null) to entities
        if (undo.size > 60) undo.removeAt(0)
        redo.clear()
    }

    fun change(next: TextFieldValue) {
        if (next.text != value.text) saveUndo()
        onValueChange(next)
    }

    fun format(kind: String, extra: String? = null) {
        if (!hasSelection) return
        saveUndo()
        onEntitiesChange(
            toggleComposerEntity(
                value.text,
                entities,
                selection.min,
                selection.max,
                kind,
                extra
            )
        )
    }

    fun restore(
        from: MutableList<Pair<TextFieldValue, List<TextEntity>>>,
        to: MutableList<Pair<TextFieldValue, List<TextEntity>>>
    ) {
        if (from.isEmpty()) return
        to += value to entities
        val previous = from.removeAt(from.lastIndex)
        onValueChange(previous.first)
        onEntitiesChange(previous.second)
    }

    val styledPreview by produceState<StyledText?>(
        null,
        value.text,
        entities,
        markdownEnabled,
        markdownAvailable
    ) {
        this.value = null
        val prepared = withContext(Dispatchers.Default) {
            preparePreview(value.text, entities, markdownEnabled && markdownAvailable)
        }
        currentCoroutineContext().ensureActive()
        this.value = prepared
    }
    val sourceStyle = remember(value.text, entities, scheme.primary) {
        VisualTransformation { raw ->
            TransformedText(buildAnnotatedString {
                append(raw)
                applyMessageEntities(
                    value.text,
                    entities,
                    scheme.primary,
                    revealSpoilers = true,
                    onLink = {})
            }, OffsetMapping.Identity)
        }
    }
    Surface(modifier.fillMaxSize(), color = scheme.surface) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
        ) {
            val wide = maxWidth >= 600.dp
            val compact = maxHeight < 300.dp
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            stringResource(R.string.dialog_markdown_close)
                        )
                    }
                    Text(
                        stringResource(R.string.dialog_editor_title),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Button(onClick = onApply, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.dialog_markdown_apply))
                    }
                }
                if (!wide && !compact) SettingsChoiceGroup(
                    options = listOf(
                        SettingsChoice(
                            stringResource(R.string.dialog_markdown_edit),
                            Icons.Outlined.Edit
                        ),
                        SettingsChoice(
                            stringResource(R.string.dialog_markdown_preview),
                            Icons.Outlined.Visibility
                        ),
                    ),
                    selectedIndex = if (previewMode) 1 else 0,
                    onSelect = { previewMode = it == 1; if (previewMode) keyboard?.hide() },
                )
                AnimatedContent(
                    targetState = if (wide) false else previewMode,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    transitionSpec = { fadeIn(motion.fastEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
                    label = "editorPreview",
                ) { showPreview ->
                    Row(Modifier.fillMaxSize()) {
                        if (wide || !showPreview) Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(horizontal = 20.dp)
                        ) {
                            if (value.text.isEmpty()) Text(
                                stringResource(R.string.dialog_message_hint),
                                color = scheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                            BasicTextField(
                                value = value,
                                onValueChange = ::change,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(vertical = 12.dp)
                                    .focusRequester(focus),
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                                cursorBrush = SolidColor(scheme.primary),
                                visualTransformation = sourceStyle,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            )
                            LaunchedEffect(Unit) { focus.requestFocus() }
                        }
                        if (wide || showPreview) Surface(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            color = scheme.surfaceContainerLow,
                        ) {
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(20.dp)
                            ) {
                                Text(
                                    stringResource(R.string.dialog_editor_send_preview),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = scheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(16.dp))
                                if (styledPreview == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                                else if (styledPreview!!.text.isEmpty()) Text(
                                    stringResource(R.string.dialog_message_hint),
                                    color = scheme.onSurfaceVariant
                                )
                                else RichMessageContent(
                                    text = styledPreview!!.text,
                                    entities = styledPreview!!.entities,
                                    contentColor = scheme.onSurface,
                                    linkColor = scheme.primary,
                                    revealSpoilers = spoilersRevealed,
                                    onSpoilerClick = { spoilersRevealed = true },
                                )
                            }
                        }
                    }
                }
                if (!compact) Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(
                            R.string.dialog_markdown_chars,
                            value.text.codePointCount(0, value.text.length)
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (markdownAvailable) FilterChip(
                        selected = markdownEnabled,
                        onClick = { onMarkdownEnabled(!markdownEnabled) },
                        label = { Text(stringResource(R.string.dialog_markdown_editor)) },
                        leadingIcon = { Icon(Icons.Outlined.Code, null, Modifier.size(18.dp)) },
                    ) else Text(
                        stringResource(R.string.dialog_editor_premium),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { restore(undo, redo) },
                        enabled = undo.isNotEmpty()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.Undo,
                            stringResource(R.string.dialog_markdown_undo)
                        )
                    }
                    IconButton(
                        onClick = { restore(redo, undo) },
                        enabled = redo.isNotEmpty()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.Redo,
                            stringResource(R.string.dialog_markdown_redo)
                        )
                    }
                    Text(
                        stringResource(if (hasSelection) R.string.dialog_editor_selection else R.string.dialog_editor_select_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (compact) IconButton(
                        onClick = { more = true },
                        enabled = hasSelection
                    ) {
                        Icon(
                            Icons.Outlined.MoreHoriz,
                            stringResource(R.string.dialog_markdown_format)
                        )
                    }
                    if (onEmoji != null) IconButton(onClick = onEmoji) {
                        Icon(
                            Icons.Outlined.EmojiEmotions,
                            stringResource(R.string.dialog_emoji_open)
                        )
                    }
                    if (!wide) IconButton(onClick = {
                        previewMode = !previewMode; if (previewMode) keyboard?.hide()
                    }) {
                        Icon(
                            if (previewMode) Icons.Outlined.Edit else Icons.Outlined.Visibility,
                            stringResource(if (previewMode) R.string.dialog_markdown_edit else R.string.dialog_markdown_preview)
                        )
                    }
                }
                AnimatedVisibility(
                    visible = !compact && (wide || !previewMode),
                    enter = fadeIn(motion.fastEffectsSpec()),
                    exit = fadeOut(motion.fastEffectsSpec()),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        HorizontalFloatingToolbar(expanded = true) {
                            listOf(
                                Triple(
                                    "bold",
                                    R.string.dialog_markdown_bold,
                                    Icons.Outlined.FormatBold
                                ),
                                Triple(
                                    "italic",
                                    R.string.dialog_markdown_italic,
                                    Icons.Outlined.FormatItalic
                                ),
                                Triple(
                                    "underline",
                                    R.string.dialog_markdown_underline,
                                    Icons.Outlined.FormatUnderlined
                                ),
                                Triple(
                                    "strike",
                                    R.string.dialog_markdown_strike,
                                    Icons.Outlined.FormatStrikethrough
                                ),
                                Triple(
                                    "spoiler",
                                    R.string.dialog_markdown_spoiler,
                                    Icons.Outlined.VisibilityOff
                                ),
                            ).forEach { (kind, label, image) ->
                                val selected =
                                    hasSelection && entities.any { it.kind == kind && it.offset <= selection.min && it.offset + it.length >= selection.max }
                                FilledTonalIconButton(
                                    onClick = { format(kind) }, enabled = hasSelection,
                                    shapes = ExpressiveDefaults.iconButtonShapes(),
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = if (selected) scheme.primaryContainer else scheme.surfaceContainer,
                                        contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                                    ),
                                ) { Icon(image, stringResource(label)) }
                            }
                            IconButton(onClick = { more = true }, enabled = hasSelection) {
                                Icon(
                                    Icons.Outlined.MoreHoriz,
                                    stringResource(R.string.dialog_markdown_format)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (more) ModalBottomSheet(onDismissRequest = { more = false }) {
        Column(
            Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                stringResource(R.string.dialog_markdown_format),
                Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                style = MaterialTheme.typography.titleLarge
            )
            listOf(
                Triple("bold", R.string.dialog_markdown_bold, Icons.Outlined.FormatBold),
                Triple("italic", R.string.dialog_markdown_italic, Icons.Outlined.FormatItalic),
                Triple(
                    "underline",
                    R.string.dialog_markdown_underline,
                    Icons.Outlined.FormatUnderlined
                ),
                Triple(
                    "strike",
                    R.string.dialog_markdown_strike,
                    Icons.Outlined.FormatStrikethrough
                ),
                Triple("spoiler", R.string.dialog_markdown_spoiler, Icons.Outlined.VisibilityOff),
                Triple("code", R.string.dialog_markdown_code, Icons.Outlined.Code),
                Triple("pre", R.string.dialog_markdown_code_block, Icons.Outlined.DataObject),
                Triple("blockquote", R.string.dialog_markdown_quote, Icons.Outlined.FormatQuote),
                Triple(
                    "collapsed",
                    R.string.dialog_editor_expandable_quote,
                    Icons.Outlined.UnfoldMore
                ),
                Triple("text_url", R.string.dialog_markdown_link, Icons.Outlined.Link),
                Triple("clear", R.string.dialog_editor_clear, Icons.Outlined.FormatClear),
            ).forEach { (kind, label, image) ->
                TextButton(
                    onClick = {
                        more = false
                        when (kind) {
                            "text_url" -> {
                                linkUrl =
                                    entities.firstOrNull { it.kind == "text_url" && it.offset <= selection.min && it.offset + it.length >= selection.max }?.url
                                        ?: "https://"; linkDialog = true
                            }

                            "pre" -> {
                                language = ""; codeDialog = true
                            }

                            "collapsed" -> format("blockquote", "collapsed")
                            else -> format(kind)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp)
                ) {
                    Icon(image, null); Spacer(Modifier.width(16.dp)); Text(
                    stringResource(label),
                    Modifier.weight(1f)
                )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (linkDialog || codeDialog) AlertDialog(
        onDismissRequest = { linkDialog = false; codeDialog = false },
        title = { Text(stringResource(if (linkDialog) R.string.dialog_markdown_link else R.string.dialog_markdown_code_block)) },
        text = {
            OutlinedTextField(
                value = if (linkDialog) linkUrl else language,
                onValueChange = { if (linkDialog) linkUrl = it else language = it },
                label = { Text(stringResource(if (linkDialog) R.string.dialog_editor_url else R.string.dialog_editor_language)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = if (linkDialog) KeyboardType.Uri else KeyboardType.Text),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                if (linkDialog) format("text_url", linkUrl.trim()) else format(
                    "pre",
                    language.trim().ifEmpty { null })
                linkDialog = false; codeDialog = false
            }, enabled = !linkDialog || entityHref("text_url", linkUrl.trim(), "") != null) {
                Text(
                    stringResource(R.string.dialog_markdown_apply)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { linkDialog = false; codeDialog = false }) {
                Text(
                    stringResource(R.string.dialog_cancel)
                )
            }
        },
    )
}
