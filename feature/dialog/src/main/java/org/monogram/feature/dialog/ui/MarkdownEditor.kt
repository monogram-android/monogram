package org.monogram.feature.dialog.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.IconButtonDefaults
import org.monogram.core.ui.menu.AppMenuDivider
import org.monogram.core.ui.menu.AppMenuGroup
import org.monogram.core.ui.menu.AppMenuItem
import org.monogram.core.ui.menu.AppMenuPopup
import org.monogram.core.ui.menu.AppMenuSurface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.monogram.core.ui.ExpressiveDefaults
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.dialog.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun shouldShowFullScreenEditor(text: String): Boolean {
    val display = collapseCustomEmojiMarkdown(text)?.text?.text ?: text
    if (display.isBlank()) return false
    return display.codePointCount(0, display.length) > 50 ||
        '\n' in display ||
        needsFullScreenEditor(display)
}

internal fun needsFullScreenEditor(text: String): Boolean {
    var lines = 0
    var hasFormatting = false
    for (line in text.lineSequence()) {
        lines++
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```") || trimmed.startsWith("$$")) return true
        if (trimmed.startsWith("> ") || "**" in line || "__" in line || "](" in line) {
            hasFormatting = true
        }
    }
    return hasFormatting && lines >= 4
}

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
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)
    val scheme = MaterialTheme.colorScheme
    val markup = LocalMarkupParser.current
    var previewMode by rememberSaveable { mutableStateOf(false) }
    var showLink by rememberSaveable { mutableStateOf(false) }
    var linkUrl by rememberSaveable { mutableStateOf("https://") }
    var showLanguages by remember { mutableStateOf(false) }
    val languages by produceState(emptyList<String>(), markup) {
        this.value = withContext(Dispatchers.Default) { markup.supportedHighlightLanguages() }
    }
    val undo = remember { mutableStateListOf<TextFieldValue>() }
    val redo = remember { mutableStateListOf<TextFieldValue>() }
    val focus = remember { FocusRequester() }
    val words = remember(value.text) { countEditorWords(value.text) }
    fun commit(next: TextFieldValue) {
        pushEditorUndo(value, next, undo, redo)
        onValueChange(next)
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = scheme.surface,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            val compact = maxHeight < 240.dp
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = if (compact) 2.dp else 8.dp)) {
                EditorHeader(
                    canUndo = undo.isNotEmpty(),
                    canRedo = redo.isNotEmpty(),
                    onDismiss = onDismiss,
                    onUndo = {
                        if (undo.isEmpty()) return@EditorHeader
                        val previous = undo.removeAt(undo.lastIndex)
                        redo += value
                        onValueChange(previous)
                    },
                    onRedo = {
                        if (redo.isEmpty()) return@EditorHeader
                        val next = redo.removeAt(redo.lastIndex)
                        undo += value
                        onValueChange(next)
                    },
                    onApply = onApply,
                    compact = compact,
                    previewMode = previewMode,
                    onTogglePreview = { previewMode = !previewMode },
                    compactActions = {
                        if (compact && !previewMode) {
                            CompactFormattingMenu(value, languages, ::commit) { showLink = true }
                        }
                    },
                )
                if (!compact) {
                    PrimaryTabRow(selectedTabIndex = if (previewMode) 1 else 0) {
                        Tab(
                            selected = !previewMode,
                            onClick = { previewMode = false },
                            text = { Text(stringResource(R.string.dialog_markdown_edit)) },
                        )
                        Tab(
                            selected = previewMode,
                            onClick = { previewMode = true },
                            text = { Text(stringResource(R.string.dialog_markdown_preview)) },
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.dialog_markdown_chars, value.text.codePointCount(0, value.text.length)),
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.dialog_markdown_words, words),
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    color = scheme.surface,
                ) {
                    AnimatedContent(
                        targetState = previewMode,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "markdownEditorBody",
                    ) { previewing ->
                        if (previewing) {
                            RichMessageContent(
                                text = value.text,
                                entities = emptyList(),
                                contentColor = scheme.onSurface,
                                linkColor = scheme.primary,
                                revealSpoilers = true,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        } else {
                            LaunchedEffect(Unit) { focus.requestFocus() }
                            BasicTextField(
                                value = value,
                                onValueChange = ::commit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                                    .focusRequester(focus),
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                                cursorBrush = SolidColor(scheme.primary),
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Sentences,
                                    keyboardType = KeyboardType.Text,
                                ),
                            )
                        }
                    }
                }
                AnimatedVisibility(visible = !previewMode && !compact) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .height(56.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = scheme.surfaceContainer,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            FormatIcon(Icons.Outlined.FormatBold, stringResource(R.string.dialog_markdown_bold)) {
                                commit(wrapMarkdown(value, "**"))
                            }
                            FormatIcon(Icons.Outlined.FormatItalic, stringResource(R.string.dialog_markdown_italic)) {
                                commit(wrapMarkdown(value, "*"))
                            }
                            FormatIcon(Icons.Outlined.FormatUnderlined, stringResource(R.string.dialog_markdown_underline)) {
                                commit(wrapMarkdown(value, "__"))
                            }
                            FormatIcon(Icons.Outlined.FormatStrikethrough, stringResource(R.string.dialog_markdown_strike)) {
                                commit(wrapMarkdown(value, "~~"))
                            }
                            FormatIcon(Icons.Outlined.VisibilityOff, stringResource(R.string.dialog_markdown_spoiler)) {
                                commit(wrapMarkdown(value, "||"))
                            }
                            FormatIcon(Icons.Outlined.Code, stringResource(R.string.dialog_markdown_code)) {
                                commit(wrapMarkdown(value, "`"))
                            }
                            Box {
                                FormatIcon(Icons.Outlined.DataObject, stringResource(R.string.dialog_markdown_code_block)) {
                                    showLanguages = true
                                }
                                AppMenuPopup(
                                    expanded = showLanguages,
                                    onDismiss = { showLanguages = false },
                                ) {
                                    AppMenuSurface(scrollState = rememberScrollState(), modifier = Modifier.heightIn(max = 320.dp)) {
                                        AppMenuGroup {
                                            AppMenuItem(
                                                text = stringResource(R.string.dialog_markdown_plain_code),
                                                onClick = {
                                                    commit(wrapCodeBlock(value, ""))
                                                    showLanguages = false
                                                },
                                            )
                                            languages.forEach { language ->
                                                AppMenuItem(
                                                    text = language,
                                                    onClick = {
                                                        commit(wrapCodeBlock(value, language))
                                                        showLanguages = false
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            FormatIcon(Icons.Outlined.FormatQuote, stringResource(R.string.dialog_markdown_quote)) {
                                commit(wrapQuote(value))
                            }
                            FormatIcon(Icons.Outlined.Link, stringResource(R.string.dialog_markdown_link)) {
                                linkUrl = "https://"
                                showLink = true
                            }
                        }
                    }
                }
            }
        }
    }
    if (showLink) {
        AlertDialog(
            onDismissRequest = { showLink = false },
            title = { Text(stringResource(R.string.dialog_markdown_link)) },
            text = {
                OutlinedTextField(
                    value = linkUrl,
                    onValueChange = { linkUrl = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val url = linkUrl.trim().ifBlank { "https://" }
                        commit(wrapMarkdown(value, "[", "]($url)"))
                        showLink = false
                    },
                ) {
                    Text(stringResource(R.string.dialog_markdown_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLink = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EditorHeader(
    canUndo: Boolean,
    canRedo: Boolean,
    onDismiss: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onApply: () -> Unit,
    compact: Boolean,
    previewMode: Boolean,
    onTogglePreview: () -> Unit,
    compactActions: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        HeaderButton(
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            description = stringResource(R.string.dialog_markdown_close),
            onClick = onDismiss,
        )
        if (compact) {
            Spacer(Modifier.weight(1f))
        } else {
            Text(
                text = stringResource(R.string.dialog_message_hint),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        compactActions()
        if (compact) EditorAction(
            icon = if (previewMode) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            description = stringResource(if (previewMode) R.string.dialog_markdown_edit else R.string.dialog_markdown_preview),
            selected = previewMode,
            onClick = onTogglePreview,
        )
        HeaderButton(
            icon = Icons.AutoMirrored.Outlined.Undo,
            description = stringResource(R.string.dialog_markdown_undo),
            enabled = canUndo,
            onClick = onUndo,
        )
        HeaderButton(
            icon = Icons.AutoMirrored.Outlined.Redo,
            description = stringResource(R.string.dialog_markdown_redo),
            enabled = canRedo,
            onClick = onRedo,
        )
        FilledIconButton(
            onClick = onApply,
            modifier = Modifier.size(48.dp),
            shapes = ExpressiveDefaults.iconButtonShapes(),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = scheme.primary,
                contentColor = scheme.onPrimary,
            ),
        ) {
            Icon(Icons.Outlined.Check, contentDescription = stringResource(R.string.dialog_markdown_apply))
        }
    }
}

@Composable
private fun CompactFormattingMenu(
    value: TextFieldValue,
    languages: List<String>,
    onChange: (TextFieldValue) -> Unit,
    onLink: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FormatIcon(Icons.Outlined.TextFields, stringResource(R.string.dialog_markdown_format)) { expanded = true }
        AppMenuPopup(
            expanded = expanded,
            onDismiss = { expanded = false },
        ) {
            AppMenuSurface(scrollState = rememberScrollState(), modifier = Modifier.heightIn(max = 360.dp)) {
                AppMenuGroup {
                    listOf(
                        R.string.dialog_markdown_bold to "**",
                        R.string.dialog_markdown_italic to "*",
                        R.string.dialog_markdown_underline to "__",
                        R.string.dialog_markdown_strike to "~~",
                        R.string.dialog_markdown_spoiler to "||",
                        R.string.dialog_markdown_code to "`",
                    ).forEach { (label, delimiter) ->
                        AppMenuItem(
                            text = stringResource(label),
                            onClick = { onChange(wrapMarkdown(value, delimiter)); expanded = false },
                        )
                    }
                    AppMenuItem(
                        text = stringResource(R.string.dialog_markdown_quote),
                        onClick = { onChange(wrapQuote(value)); expanded = false },
                    )
                    AppMenuItem(
                        text = stringResource(R.string.dialog_markdown_link),
                        onClick = { expanded = false; onLink() },
                    )
                }
                AppMenuDivider()
                AppMenuGroup {
                    (listOf("") + languages).forEach { language ->
                        AppMenuItem(
                            text = language.ifBlank { stringResource(R.string.dialog_markdown_code_block) },
                            onClick = { onChange(wrapCodeBlock(value, language)); expanded = false },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HeaderButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp),
    ) {
        Icon(icon, contentDescription = description)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EditorAction(
    icon: ImageVector,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        shapes = ExpressiveDefaults.iconButtonShapes(),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (selected) scheme.primaryContainer else scheme.surfaceContainerHighest,
            contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        ),
    ) {
        Icon(icon, contentDescription = description)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FormatIcon(
    image: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
    ) {
        Icon(image, contentDescription = description)
    }
}

@Preview(showBackground = true, name = "Editor light")
@Preview(
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    name = "Editor dark",
)
@Composable
private fun MarkdownEditorPreview() {
    MonogramTheme(dynamicColor = false) {
        MarkdownEditorScreen(
            value = TextFieldValue("**hello** and `code`"),
            onValueChange = {},
            onApply = {},
            onDismiss = {},
        )
    }
}
