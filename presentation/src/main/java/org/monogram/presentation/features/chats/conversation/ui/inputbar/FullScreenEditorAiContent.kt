package org.monogram.presentation.features.chats.conversation.ui.inputbar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.FormatClear
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.domain.repository.StickerRepository
import org.monogram.domain.repository.TextCompositionStyleModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsTextField
import org.monogram.presentation.features.chats.conversation.ui.message.addEmojiStyle
import org.monogram.presentation.features.profile.logs.components.calculateDiff
import org.monogram.presentation.features.stickers.ui.view.StickerImage
import java.util.Locale

private data class AiPanelLanguageOption(
    val code: String,
    val label: String
)

private data class AiPanelModeOption(
    val mode: AiEditorMode,
    val label: String,
    val icon: ImageVector
)

private val AI_PANEL_LANGUAGE_CODES = listOf(
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

private val DEFAULT_AI_PANEL_STYLES = listOf(
    TextCompositionStyleModel(
        name = "formal",
        customEmojiId = 5357080225463149588L,
        title = "Formal"
    ),
    TextCompositionStyleModel(
        name = "short",
        customEmojiId = 5350460637182993292L,
        title = "Short"
    ),
    TextCompositionStyleModel(
        name = "tribal",
        customEmojiId = 5470159421512359552L,
        title = "Tribal"
    ),
    TextCompositionStyleModel(name = "corp", customEmojiId = 5359785904535774578L, title = "Corp"),
    TextCompositionStyleModel(
        name = "biblical",
        customEmojiId = 5350571717922167592L,
        title = "Biblical"
    ),
    TextCompositionStyleModel(
        name = "viking",
        customEmojiId = 5350341795437911403L,
        title = "Viking"
    ),
    TextCompositionStyleModel(name = "zen", customEmojiId = 5442983582882601962L, title = "Zen")
)

private fun buildAiPanelLanguageOptions(): List<AiPanelLanguageOption> {
    return AI_PANEL_LANGUAGE_CODES.distinct().map { code ->
        val label = Locale(code).getDisplayLanguage(Locale.getDefault()).ifBlank { code }
        AiPanelLanguageOption(
            code = code,
            label = label.replaceFirstChar { char -> char.titlecase(Locale.getDefault()) }
        )
    }
}

@Composable
private fun resolveAiPanelStyleTitle(style: TextCompositionStyleModel): String {
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
internal fun FullScreenEditorAiSheetContent(
    visible: Boolean,
    mode: AiEditorMode,
    loading: Boolean,
    errorMessage: String?,
    styles: List<TextCompositionStyleModel>,
    stickerRepository: StickerRepository,
    selectedStyleName: String,
    translateLanguage: String,
    prompt: String,
    addEmojis: Boolean,
    emojiFontFamily: FontFamily,
    inlineContent: Map<String, InlineTextContent>,
    originalText: String,
    resultText: AnnotatedString?,
    resultPlainText: String?,
    showDiffMode: Boolean,
    supportsPromptBasedAi: Boolean,
    onDismiss: () -> Unit,
    onModeChange: (AiEditorMode) -> Unit,
    onToggleDiffMode: () -> Unit,
    onStyleSelected: (String) -> Unit,
    onTranslateLanguageChange: (String) -> Unit,
    onPromptChange: (String) -> Unit,
    onAddEmojisChange: (Boolean) -> Unit,
    onRun: () -> Unit,
    onApplyResult: () -> Unit
) {
    if (!visible) return

    val languageOptions = remember { buildAiPanelLanguageOptions() }
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
    val effectiveStyles = if (styles.isEmpty()) DEFAULT_AI_PANEL_STYLES else styles
    val modeOptions = listOf(
        AiPanelModeOption(
            mode = AiEditorMode.Translate,
            label = stringResource(R.string.editor_ai_tab_translate),
            icon = Icons.Outlined.Translate
        ),
        if (effectiveStyles.isNotEmpty()) {
            AiPanelModeOption(
                mode = AiEditorMode.Stylize,
                label = stringResource(R.string.editor_ai_tab_stylize),
                icon = Icons.Outlined.AutoAwesome
            )
        } else {
            null
        },
        if (supportsPromptBasedAi) {
            AiPanelModeOption(
                mode = AiEditorMode.Generate,
                label = stringResource(R.string.editor_ai_tab_generate),
                icon = Icons.Outlined.Description
            )
        } else {
            null
        },
        AiPanelModeOption(
            mode = AiEditorMode.Fix,
            label = stringResource(R.string.editor_ai_tab_fix),
            icon = Icons.Outlined.FormatClear
        )
    ).filterNotNull()
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
    val sourcePreviewTitle = if (mode == AiEditorMode.Generate && originalText.isBlank()) {
        stringResource(R.string.editor_ai_prompt_label)
    } else {
        stringResource(R.string.editor_ai_original)
    }
    val sourcePreviewText = when {
        mode == AiEditorMode.Generate && prompt.isBlank() -> stringResource(R.string.editor_ai_prompt_placeholder)
        mode == AiEditorMode.Generate && originalText.isBlank() -> prompt
        else -> originalText
    }
    val formattedSourceText = remember(sourcePreviewText, emojiFontFamily) {
        buildEmojiText(sourcePreviewText, emojiFontFamily)
    }
    val formattedDiffText = remember(diffText, emojiFontFamily) {
        diffText?.let { buildEmojiText(it, emojiFontFamily) }
    }
    val formattedResultText = remember(resultText, emojiFontFamily) {
        resultText?.let { buildEmojiText(it, emojiFontFamily) }
    }
    val actionButtonText = if (resultText != null) {
        stringResource(R.string.editor_ai_apply_result)
    } else {
        when (mode) {
            AiEditorMode.Translate -> stringResource(R.string.editor_ai_run_translate)
            AiEditorMode.Stylize -> stringResource(R.string.editor_ai_run_stylize)
            AiEditorMode.Generate -> stringResource(R.string.editor_ai_run_generate)
            AiEditorMode.Fix -> stringResource(R.string.editor_ai_run_fix)
        }
    }

    LaunchedEffect(styles, mode, selectedStyleName) {
        if (styles.isEmpty() && mode == AiEditorMode.Stylize) {
            onModeChange(AiEditorMode.Translate)
            return@LaunchedEffect
        }

        if (mode == AiEditorMode.Stylize && styles.isNotEmpty() && styles.none { it.name == selectedStyleName }) {
            onStyleSelected(styles.first().name)
        }
    }

    LaunchedEffect(styles) {
        if (styles.any { it.customEmojiId != 0L }) {
            runCatching { stickerRepository.loadCustomEmojiStickerSets() }
        }
    }

    LaunchedEffect(supportsPromptBasedAi, mode) {
        if (!supportsPromptBasedAi && mode == AiEditorMode.Generate) {
            onModeChange(AiEditorMode.Translate)
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
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Box(
                                modifier = Modifier.size(40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.editor_ai_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.editor_ai_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.action_close)
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AiModeOptionsGrid(
                            options = modeOptions,
                            selectedMode = mode,
                            loading = loading,
                            onModeChange = onModeChange
                        )
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
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box {
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            onClick = { if (!loading) languageMenuExpanded = true },
                                            enabled = !loading,
                                            shape = RoundedCornerShape(16.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerHighest
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(52.dp)
                                                    .padding(horizontal = 14.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Translate,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Column(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .padding(start = 10.dp)
                                                ) {
                                                    Text(
                                                        text = selectedLanguage?.label
                                                            ?: selectedLanguageCode,
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = stringResource(R.string.editor_ai_select_language),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                Icon(
                                                    imageVector = Icons.Outlined.KeyboardArrowDown,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }

                                        DropdownMenu(
                                            expanded = languageMenuExpanded,
                                            onDismissRequest = { languageMenuExpanded = false },
                                            shape = RoundedCornerShape(16.dp),
                                            tonalElevation = 0.dp,
                                            shadowElevation = 0.dp
                                        ) {
                                            languageOptions.forEach { option ->
                                                val selected = option.code == selectedLanguageCode
                                                DropdownMenuItem(
                                                    text = { Text(option.label) },
                                                    leadingIcon = {
                                                        Icon(
                                                            imageVector = Icons.Outlined.Translate,
                                                            contentDescription = null
                                                        )
                                                    },
                                                    trailingIcon = if (selected) {
                                                        {
                                                            Icon(
                                                                imageVector = Icons.Filled.Check,
                                                                contentDescription = null
                                                            )
                                                        }
                                                    } else null,
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

                        AiEditorMode.Generate -> {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SettingsTextField(
                                    value = prompt,
                                    onValueChange = onPromptChange,
                                    placeholder = stringResource(R.string.editor_ai_prompt_placeholder),
                                    icon = Icons.Outlined.Description,
                                    position = ItemPosition.STANDALONE,
                                    modifier = Modifier.fillMaxWidth(),
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    minLines = 3,
                                    maxLines = 5
                                )

                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    shape = RoundedCornerShape(24.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Box {
                                            Surface(
                                                modifier = Modifier.fillMaxWidth(),
                                                onClick = {
                                                    if (!loading) languageMenuExpanded = true
                                                },
                                                enabled = !loading,
                                                shape = RoundedCornerShape(16.dp),
                                                color = MaterialTheme.colorScheme.surfaceContainerHighest
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(52.dp)
                                                        .padding(horizontal = 14.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Outlined.Translate,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Column(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .padding(start = 10.dp)
                                                    ) {
                                                        Text(
                                                            text = selectedLanguage?.label
                                                                ?: selectedLanguageCode,
                                                            style = MaterialTheme.typography.bodyLarge,
                                                            color = MaterialTheme.colorScheme.onSurface,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Text(
                                                            text = stringResource(R.string.editor_ai_select_language),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                    Icon(
                                                        imageVector = Icons.Outlined.KeyboardArrowDown,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }

                                            DropdownMenu(
                                                expanded = languageMenuExpanded,
                                                onDismissRequest = { languageMenuExpanded = false },
                                                shape = RoundedCornerShape(16.dp),
                                                tonalElevation = 0.dp,
                                                shadowElevation = 0.dp
                                            ) {
                                                languageOptions.forEach { option ->
                                                    val selected =
                                                        option.code == selectedLanguageCode
                                                    DropdownMenuItem(
                                                        text = { Text(option.label) },
                                                        leadingIcon = {
                                                            Icon(
                                                                imageVector = Icons.Outlined.Translate,
                                                                contentDescription = null
                                                            )
                                                        },
                                                        trailingIcon = if (selected) {
                                                            {
                                                                Icon(
                                                                    imageVector = Icons.Filled.Check,
                                                                    contentDescription = null
                                                                )
                                                            }
                                                        } else null,
                                                        onClick = {
                                                            onTranslateLanguageChange(option.code)
                                                            languageMenuExpanded = false
                                                        }
                                                    )
                                                }
                                            }
                                        }

                                        AiCompactToggleTile(
                                            icon = Icons.Outlined.EmojiEmotions,
                                            title = stringResource(R.string.editor_ai_add_emojis),
                                            checked = addEmojis,
                                            iconColor = MaterialTheme.colorScheme.primary,
                                            enabled = !loading,
                                            onCheckedChange = onAddEmojisChange
                                        )
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
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    if (effectiveStyles.isNotEmpty()) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            effectiveStyles.forEach { style ->
                                                AiStyleChip(
                                                    selected = style.name == selectedStyleName,
                                                    onClick = { onStyleSelected(style.name) },
                                                    label = resolveAiPanelStyleTitle(style),
                                                    emojiFileId = styleEmojiFileIds[style.customEmojiId],
                                                    enabled = !loading,
                                                    stickerRepository = stickerRepository
                                                )
                                            }
                                        }
                                    }

                                    AiCompactToggleTile(
                                        icon = Icons.Outlined.EmojiEmotions,
                                        title = stringResource(R.string.editor_ai_add_emojis),
                                        checked = addEmojis,
                                        iconColor = MaterialTheme.colorScheme.primary,
                                        enabled = !loading,
                                        onCheckedChange = onAddEmojisChange
                                    )
                                }
                            }
                        }

                        AiEditorMode.Fix -> Unit
                    }
                }

                AnimatedContent(
                    targetState = resultText != null,
                    label = "ai_result_visibility"
                ) { hasResult ->
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
                                    text = sourcePreviewTitle,
                                    style = sectionTitleStyle,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = formattedSourceText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    } else {
                        AnimatedContent(
                            targetState = showDiffMode,
                            label = "ai_result_view_mode"
                        ) { isDiffMode ->
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
                                                text = sourcePreviewTitle,
                                                style = sectionTitleStyle,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = formattedSourceText,
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
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(text = actionButtonText, fontWeight = FontWeight.Bold)
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
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
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
private fun AiModeOptionsGrid(
    options: List<AiPanelModeOption>,
    selectedMode: AiEditorMode,
    loading: Boolean,
    onModeChange: (AiEditorMode) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup()
    ) {
        val columns = when {
            maxWidth < 360.dp -> 2
            maxWidth < 520.dp -> 3
            else -> 4
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.chunked(columns).forEach { rowOptions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowOptions.forEach { option ->
                        val selected = selectedMode == option.mode
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 64.dp),
                            onClick = { if (!loading) onModeChange(option.mode) },
                            enabled = !loading,
                            shape = RoundedCornerShape(18.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = option.icon,
                                    contentDescription = null,
                                    tint = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = option.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    repeat(columns - rowOptions.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun AiCompactToggleTile(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    iconColor: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val containerColor = if (checked) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (checked) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .clickable(
                    enabled = enabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onCheckedChange(!checked) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            color = if (checked) {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (checked) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            iconColor
                        },
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun buildEmojiText(text: String, emojiFontFamily: FontFamily): AnnotatedString {
    return buildAnnotatedString {
        append(text)
        if (emojiFontFamily != FontFamily.Default) {
            addEmojiStyle(text, emojiFontFamily)
        }
    }
}

private fun buildEmojiText(text: AnnotatedString, emojiFontFamily: FontFamily): AnnotatedString {
    return if (emojiFontFamily == FontFamily.Default) {
        text
    } else {
        buildAnnotatedString {
            append(text)
            addEmojiStyle(text.text, emojiFontFamily)
        }
    }
}
