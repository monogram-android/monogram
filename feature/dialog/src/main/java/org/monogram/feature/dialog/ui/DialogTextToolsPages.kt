package org.monogram.feature.dialog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import kotlinx.coroutines.launch
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.AiTextTone
import org.monogram.core.models.Poll
import org.monogram.core.models.TextToolText
import org.monogram.core.models.poll
import org.monogram.core.ui.components.LanguagePickerSheet
import org.monogram.core.ui.components.languageDisplayName
import org.monogram.core.ui.loading.MonogramLoading
import org.monogram.core.ui.loading.MonogramLoadingInlineSize
import org.monogram.core.ui.menu.AppMenuDivider
import org.monogram.core.ui.menu.AppMenuItem
import org.monogram.core.ui.menu.AppMenuPopup
import org.monogram.core.ui.menu.AppMenuSurface
import org.monogram.feature.dialog.AiComposerState
import org.monogram.feature.dialog.AiTextTab
import org.monogram.feature.dialog.DialogComponent
import org.monogram.feature.dialog.DialogStore
import org.monogram.feature.dialog.DialogTextToolsState
import org.monogram.feature.dialog.PollTranslation
import org.monogram.feature.dialog.R
import org.monogram.feature.dialog.TextToolsAction

@Composable
internal fun DialogTranslationMenu(
    state: DialogTextToolsState,
    onAction: (TextToolsAction) -> Unit,
) {
    if (!state.providerConfigured || !state.chatEnabledPreference) return
    val availability = state.availability
    if (availability?.chatTranslation != true && state.availabilityError == null) return
    val peerDisabled = availability?.peerTranslationsDisabled == true
    if (peerDisabled && state.customProvider && state.availabilityError == null) return
    val canTranslate = state.showChatTranslation && state.chatAllowed && !state.peerSaving
    val translating = state.chatEnabled && canTranslate
    var expanded by remember { mutableStateOf(false) }
    var choosingLanguage by rememberSaveable { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                val description = stringResource(R.string.dialog_tools_translate)
                if (state.chatLoading || state.peerSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(18.dp)
                            .semantics { contentDescription = description },
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        Icons.Outlined.Translate,
                        contentDescription = description,
                        tint = if (translating) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        AppMenuPopup(expanded, onDismiss = { expanded = false }, alignToAnchorEnd = true) {
            AppMenuSurface(scrollState = rememberScrollState()) {
                when {
                    state.availabilityError != null -> {
                        AppMenuItem(
                            text = stringResource(R.string.dialog_retry_load),
                            icon = Icons.Outlined.Refresh,
                            onClick = {
                                expanded = false
                                onAction(TextToolsAction.RetryAvailability)
                            },
                        )
                    }

                    !state.chatAllowed -> {
                        AppMenuItem(
                            text = stringResource(R.string.dialog_tools_premium_chat),
                            icon = Icons.Outlined.Translate,
                            labelMaxLines = 4,
                            enabled = false,
                            onClick = {},
                        )
                    }

                    !peerDisabled -> {
                        AppMenuItem(
                            text = stringResource(
                                if (translating) R.string.dialog_tools_show_original else R.string.dialog_tools_translate_chat,
                            ),
                            icon = if (translating) Icons.Outlined.Visibility else Icons.Outlined.Translate,
                            labelMaxLines = 2,
                            enabled = canTranslate,
                            onClick = {
                                expanded = false
                                onAction(TextToolsAction.ChatEnabled(!translating))
                            },
                        )
                        AppMenuItem(
                            text = stringResource(
                                R.string.dialog_tools_target_language,
                                languageDisplayName(state.targetLanguage),
                            ),
                            icon = Icons.Outlined.Language,
                            labelMaxLines = 2,
                            enabled = canTranslate,
                            onClick = {
                                expanded = false
                                choosingLanguage = true
                            },
                        )
                        if (state.chatError != null && translating) {
                            AppMenuItem(
                                text = stringResource(R.string.dialog_retry_load),
                                icon = Icons.Outlined.Refresh,
                                onClick = {
                                    expanded = false
                                    onAction(TextToolsAction.RetryChat)
                                },
                            )
                        }
                    }
                }
                if (availability?.chatTranslation == true && !state.customProvider) {
                    if (!peerDisabled) AppMenuDivider()
                    AppMenuItem(
                        text = stringResource(R.string.dialog_tools_peer_disabled),
                        trailing = {
                            Checkbox(
                                checked = peerDisabled,
                                onCheckedChange = null,
                                enabled = !state.peerSaving
                            )
                        },
                        labelMaxLines = 2,
                        enabled = !state.peerSaving,
                        onClick = {
                            expanded = false
                            onAction(TextToolsAction.PeerDisabled(!peerDisabled))
                        },
                    )
                }
            }
        }
    }
    if (choosingLanguage && canTranslate) {
        LanguagePicker(state.targetLanguage, onSelect = {
            choosingLanguage = false
            if (it != state.targetLanguage) onAction(TextToolsAction.Language(it))
        }, onDismiss = { choosingLanguage = false })
    }
}

@Composable
internal fun DialogTextToolsPages(
    component: DialogComponent,
    state: DialogStore.State,
    composer: MutableState<TextFieldValue>,
    inEditor: Boolean = false,
    onApplyEditor: ((AiComposerState, TextToolText) -> Unit)? = null,
) {
    val editorSlot by component.editorSlot.subscribeAsState()
    if ((editorSlot.child != null) != inEditor) return
    val tools = state.textTools
    val motion = MaterialTheme.motionScheme
    val slot by component.textToolsSlot.subscribeAsState()
    tools.messageResult?.takeIf { !inEditor && slot.child == null }?.let { result ->
        TextToolsPage(
            title = stringResource(if (result.summary) R.string.dialog_tools_summary_result else R.string.dialog_tools_translate),
            onBack = { component.onTextTools(TextToolsAction.CloseResult) },
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LanguageButton(tools.targetLanguage) {
                    component.onTextTools(
                        TextToolsAction.Language(
                            it
                        )
                    )
                }
                Text(
                    stringResource(if (result.summary) R.string.dialog_tools_summary_scope else R.string.dialog_tools_translation_scope),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (result.summary && !tools.customProvider) {
                    LaunchedEffect(result.source.id.id) { component.onTextTools(TextToolsAction.LoadTones) }
                    if (tools.tones.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            tools.tones.take(6).forEach { tone ->
                                FilterChip(
                                    selected = result.tone == tone.key,
                                    onClick = {
                                        component.onTextTools(
                                            TextToolsAction.SummaryTone(
                                                tone.key
                                            )
                                        )
                                    },
                                    label = { Text(tone.title) },
                                )
                            }
                        }
                    }
                }
                var showOriginal by rememberSaveable(
                    result.source.id.id, result.summary
                ) { mutableStateOf(false) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !showOriginal,
                        onClick = { showOriginal = false },
                        label = {
                            Text(stringResource(if (result.summary) R.string.dialog_tools_summary_result else R.string.dialog_tools_result))
                        })
                    FilterChip(selected = showOriginal, onClick = { showOriginal = true }, label = {
                        Text(stringResource(R.string.dialog_tools_original))
                    })
                }
                AnimatedContent(
                    targetState = showOriginal,
                    transitionSpec = {
                        fadeIn(motion.fastEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec())
                    },
                    label = "text-tool-result",
                ) { original ->
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (original) {
                                result.source.poll?.let { ToolPollText(originalPollTranslation(it)) }
                                    ?: ToolRichText(
                                        TextToolText(
                                            result.source.text.orEmpty(), result.source.entities
                                        )
                                    )
                            } else {
                                result.result?.let { ToolRichText(it) }
                                result.poll?.let { ToolPollText(it) }
                            }
                        }
                    }
                }
                if (result.loading) {
                    ToolProgress { component.onTextTools(TextToolsAction.CancelRequest) }
                }
                result.error?.let {
                    TextToolsError(
                        it, { component.onTextTools(TextToolsAction.Retry) })
                }
                if (!showOriginal && !result.loading) {
                    val text = result.result?.text ?: result.poll?.let { poll ->
                        (listOf(poll.question) + poll.answers + listOfNotNull(poll.solution)).joinToString(
                            "\n"
                        ) { it.text }
                    }
                    text?.let { ToolResultActions(it) }
                    if (text == null && result.error == null) {
                        TextButton(onClick = { component.onTextTools(TextToolsAction.Retry) }) {
                            Text(stringResource(R.string.dialog_tools_generate))
                        }
                    }
                }
            }
        }
    }
    slot.child?.let { child ->
        val configuration = child.configuration
        LaunchedEffect(configuration, tools.composer, tools.customProvider) {
            if (configuration == DialogComponent.TextToolsScreen.Settings ||
                configuration == DialogComponent.TextToolsScreen.TonesFromSettings ||
                (configuration == DialogComponent.TextToolsScreen.Composer && tools.composer == null) ||
                (configuration == DialogComponent.TextToolsScreen.Tones && tools.customProvider)
            ) component.backTextToolsScreen()
        }
        if (configuration == DialogComponent.TextToolsScreen.Settings ||
            configuration == DialogComponent.TextToolsScreen.TonesFromSettings
        ) return
        CompositionLocalProvider(LocalDialogMedia provides component.mediaRepository) {
            TextToolsPage(
                title = when (child.configuration) {
                    DialogComponent.TextToolsScreen.Composer -> {
                        stringResource(R.string.dialog_tools_ai)
                    }

                    DialogComponent.TextToolsScreen.Settings, DialogComponent.TextToolsScreen.TonesFromSettings -> {
                        stringResource(R.string.dialog_tools_settings)
                    }

                    DialogComponent.TextToolsScreen.Tones -> {
                        stringResource(
                            R.string.dialog_tools_manage_tones,
                        )
                    }
                },
                onBack = component::backTextToolsScreen,
            ) {
                when (child.configuration) {
                    DialogComponent.TextToolsScreen.Composer -> {
                        AiComposerPage(component, tools, composer, onApplyEditor)
                    }

                    DialogComponent.TextToolsScreen.Settings, DialogComponent.TextToolsScreen.TonesFromSettings -> Unit

                    DialogComponent.TextToolsScreen.Tones -> {
                        TextTonesPage(
                            component,
                            tools,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TextToolsPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onBack)
    val motion = MaterialTheme.motionScheme
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        AnimatedVisibility(
            visibleState = visible,
            enter = fadeIn(motion.fastEffectsSpec()),
            exit = fadeOut(motion.fastEffectsSpec()),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .imePadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.dialog_cancel)
                            )
                        }
                        Text(title, style = MaterialTheme.typography.titleLarge)
                    }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Box(
                            Modifier
                                .widthIn(max = 720.dp)
                                .fillMaxSize()
                        ) { content() }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiComposerPage(
    component: DialogComponent,
    tools: DialogTextToolsState,
    field: MutableState<TextFieldValue>,
    onApplyEditor: ((AiComposerState, TextToolText) -> Unit)?,
) {
    val composer = tools.composer ?: return
    val tab = composer.tab
    val tone = composer.tone
    val emojify = composer.emojify

    fun options(
        nextTab: AiTextTab = tab,
        nextTone: String? = tone,
        nextEmoji: Boolean = emojify,
    ) {
        component.onTextTools(TextToolsAction.ComposerOptions(nextTab, nextTone, nextEmoji))
    }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.dialog_tools_composer_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!tools.providerConfigured) {
            Text(
                stringResource(R.string.dialog_tools_provider_required),
                color = MaterialTheme.colorScheme.error
            )
        }
        val tabs = listOf(AiTextTab.Style, AiTextTab.Proofread)
        PrimaryScrollableTabRow(selectedTabIndex = tabs.indexOf(tab).coerceAtLeast(0)) {
            tabs.forEach { item ->
                Tab(selected = tab == item, onClick = { options(nextTab = item) }, text = {
                    Text(
                        stringResource(
                            when (item) {
                                AiTextTab.Translate -> R.string.dialog_tools_translate
                                AiTextTab.Style -> R.string.dialog_tools_style
                                AiTextTab.Proofread -> R.string.dialog_tools_proofread
                            },
                        ),
                    )
                })
            }
        }
        if (tab == AiTextTab.Translate) LanguageButton(tools.targetLanguage) {
            component.onTextTools(
                TextToolsAction.Language(it)
            )
        }
        if (tab == AiTextTab.Style) {
            if (tools.customProvider) {
                OutlinedTextField(
                    value = tone.orEmpty(),
                    onValueChange = {
                        options(
                            nextTone = it.take(1024).takeIf(String::isNotBlank)
                        )
                    },
                    label = { Text(stringResource(R.string.dialog_tools_tone_prompt)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
            if (tools.tonesLoading) CircularProgressIndicator()
            if (tools.tones.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tools.tones.forEach { item ->
                        FilterChip(
                            selected = tone == item.key,
                            onClick = { options(nextTone = item.key) },
                            label = { ToneLabel(item) })
                    }
                }
            } else if (tools.tonesLoaded) {
                Text(stringResource(R.string.dialog_tools_no_tones))
            }
            if (!tools.customProvider) {
                TextButton(onClick = component::openTextToolsTones) { Text(stringResource(R.string.dialog_tools_manage_tones)) }
            }
            PreferenceRow(
                stringResource(R.string.dialog_tools_emoji), emojify
            ) { options(nextEmoji = it) }
            tools.toneError?.let {
                TextToolsError(
                    it, { component.onTextTools(TextToolsAction.LoadTones) })
            }
        }
        Text(
            stringResource(
                if (composer.start == 0 && composer.end == composer.original.text.length) {
                    R.string.dialog_tools_original
                } else {
                    R.string.dialog_editor_selection
                },
            ),
            style = MaterialTheme.typography.labelLarge,
        )
        SelectionContainer { ToolRichText(composer.input) }
        if (composer.stale) Text(
            stringResource(R.string.dialog_tools_draft_changed),
            color = MaterialTheme.colorScheme.error
        )
        if (composer.loading) ToolProgress { component.onTextTools(TextToolsAction.CancelRequest) }
        composer.error?.let { TextToolsError(it, { component.onTextTools(TextToolsAction.Retry) }) }
        Button(
            onClick = {
                component.onTextTools(
                    TextToolsAction.Compose(
                        tab, tone, emojify && tab == AiTextTab.Style
                    )
                )
            },
            enabled = tools.providerConfigured && !composer.loading && !composer.stale && (tab != AiTextTab.Style || tone != null || emojify),
        ) {
            Text(stringResource(R.string.dialog_tools_generate))
        }
        composer.result?.let { generated ->
            HorizontalDivider()
            Text(
                stringResource(R.string.dialog_tools_result),
                style = MaterialTheme.typography.labelLarge
            )
            SelectionContainer { ToolRichText(generated.result) }
            ToolResultActions(generated.result.text)
            generated.diff?.let { diff ->
                var showDiff by rememberSaveable { mutableStateOf(false) }
                TextButton(onClick = {
                    showDiff = !showDiff
                }) { Text(stringResource(R.string.dialog_tools_changes)) }
                if (showDiff) ToolDiffText(diff)
            }
            Button(
                onClick = {
                    if (onApplyEditor != null) {
                        onApplyEditor(composer, generated.result)
                        return@Button
                    }
                    val old = field.value
                    val end = composer.start + generated.result.text.length
                    component.onTextTools(TextToolsAction.Apply)
                    if (component.state.value.textTools.composer == null) {
                        val draft = component.state.value.draft
                        field.value = old.copy(
                            text = draft,
                            selection = if (composer.start == 0 && composer.end == composer.original.text.length) {
                                TextRange(draft.length)
                            } else {
                                TextRange(composer.start, end)
                            },
                            composition = null,
                        )
                    }
                }, enabled = !composer.loading && !composer.stale
            ) { Text(stringResource(R.string.dialog_tools_replace_draft)) }
        }
        TextButton(onClick = component::closeTextToolsScreen) { Text(stringResource(R.string.dialog_cancel)) }
    }
}

@Composable
private fun TextTonesPage(
    component: DialogComponent,
    tools: DialogTextToolsState,
) {
    var edit by remember { mutableStateOf<AiTextTone?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var resolve by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<AiTextTone?>(null) }
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.dialog_tools_styles_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (tools.tonesLoading || tools.toneBusy) CircularProgressIndicator()
        tools.toneError?.let {
            TextToolsError(
                it, { component.onTextTools(TextToolsAction.LoadTones) })
        }
        tools.tones.forEach { tone ->
            ToneLabel(tone)
            if (!tone.key.startsWith("default:")) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (tone.creator) TextButton(onClick = {
                        edit = tone
                    }) { Text(stringResource(R.string.dialog_edit)) }
                    TextButton(
                        onClick = {
                            component.onTextTools(TextToolsAction.ToneExample(tone.key))
                        }, enabled = !tools.toneBusy
                    ) { Text(stringResource(R.string.dialog_tools_example)) }
                    TextButton(onClick = {
                        context.startActivity(
                            android.content.Intent.createChooser(
                                android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        android.content.Intent.EXTRA_TEXT,
                                        "https://t.me/addstyle/${tone.key}"
                                    )
                                },
                                null,
                            ),
                        )
                    }) { Text(stringResource(R.string.dialog_instant_view_share)) }
                    TextButton(onClick = {
                        if (tone.creator) deleting = tone else component.onTextTools(
                            TextToolsAction.SaveTone(
                                tone.key, true
                            )
                        )
                    }, enabled = !tools.toneBusy) {
                        Text(
                            stringResource(if (tone.creator) R.string.dialog_delete else R.string.dialog_tools_remove_tone),
                        )
                    }
                }
            }
            HorizontalDivider()
        }
        if (tools.tonesLoaded && tools.tones.isEmpty()) Text(stringResource(R.string.dialog_tools_no_tones))
        TextButton(onClick = {
            creating = true
        }) { Text(stringResource(R.string.dialog_tools_create_tone)) }
        OutlinedTextField(
            value = resolve,
            onValueChange = {
                resolve = it
            },
            singleLine = true,
            label = { Text(stringResource(R.string.dialog_tools_tone_link)) },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                component.onTextTools(TextToolsAction.ResolveTone(resolve))
            }, enabled = resolve.isNotBlank() && !tools.toneBusy
        ) { Text(stringResource(R.string.dialog_tools_preview_tone)) }
        tools.resolvedTone?.let { tone ->
            ToneLabel(tone)
            tone.prompt?.let { Text(it) }
            Row {
                TextButton(
                    onClick = {
                        component.onTextTools(TextToolsAction.ToneExample(tone.key))
                    }, enabled = !tools.toneBusy
                ) { Text(stringResource(R.string.dialog_tools_example)) }
                if (tools.tones.none {
                        it.key == tone.key
                    }) {
                    TextButton(
                        onClick = {
                            component.onTextTools(TextToolsAction.SaveTone(tone.key))
                        }, enabled = !tools.toneBusy
                    ) { Text(stringResource(R.string.dialog_tools_save_tone)) }
                }
            }
        }
        tools.toneExample?.let { ToolRichText(it) }
        TextButton(onClick = component::backFromTextToolsTones) { Text(stringResource(R.string.dialog_select_done)) }
    }
    if (creating || edit != null) {
        ToneEditor(edit, tools, component) {
            creating = false
            edit = null
        }
    }
    deleting?.let { tone ->
        AlertDialog(
            onDismissRequest = {
                deleting = null
            },
            text = { Text(stringResource(R.string.dialog_tools_delete_tone_confirm, tone.title)) },
            confirmButton = {
                TextButton(onClick = {
                    component.onTextTools(TextToolsAction.DeleteTone(tone.key))
                    deleting = null
                }) { Text(stringResource(R.string.dialog_delete)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    deleting = null
                }) { Text(stringResource(R.string.dialog_cancel)) }
            })
    }
}

@Composable
private fun ToneEditor(
    tone: AiTextTone?,
    tools: DialogTextToolsState,
    component: DialogComponent,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable(tone?.key) { mutableStateOf(tone?.title.orEmpty()) }
    var prompt by rememberSaveable(tone?.key) { mutableStateOf(tone?.prompt.orEmpty()) }
    var author by rememberSaveable(tone?.key) { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    LaunchedEffect(tools.toneBusy, tools.toneError, submitted) {
        if (submitted && !tools.toneBusy && tools.toneError == null) onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (tone == null) {
                        R.string.dialog_tools_create_tone
                    } else {
                        R.string.dialog_edit
                    },
                ),
            )
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        if (it.length <= 12) {
                            title = it
                        }
                    },
                    label = { Text(stringResource(R.string.dialog_tools_tone_title)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = {
                        if (it.length <= 1024) {
                            prompt = it
                        }
                    },
                    label = { Text(stringResource(R.string.dialog_tools_tone_prompt)) },
                    minLines = 3,
                    maxLines = 6
                )
                PreferenceRow(stringResource(R.string.dialog_tools_display_author), author) {
                    author = it
                }
                tools.toneError?.let { TextToolsError(it, null) }
                if (tools.toneBusy) CircularProgressIndicator()
            }
        },
        confirmButton = {
            TextButton(onClick = {
                component.onTextTools(
                    TextToolsAction.WriteTone(
                        tone?.key, title, prompt, tone?.emojiId ?: 0, author
                    )
                )
                submitted = true
            }, enabled = !tools.toneBusy && title.isNotBlank() && prompt.isNotBlank()) {
                Text(stringResource(R.string.dialog_tools_save_tone))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } })
}

@Composable
private fun ToneLabel(tone: AiTextTone) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (tone.emojiId != 0L) CustomEmojiGlyph(tone.emojiId, size = 24.dp)
        Text(tone.title)
    }
}

@Composable
internal fun ToolRichText(value: TextToolText) {
    MessageText(
        value.text,
        value.entities,
        MaterialTheme.colorScheme.onSurface,
        MaterialTheme.colorScheme.primary,
        revealSpoilers = true
    )
}

@Composable
private fun ToolPollText(value: PollTranslation) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ToolRichText(value.question)
        value.answers.forEach { answer ->
            ToolRichText(
                TextToolText(
                    "• ${answer.text}", answer.entities.map { it.copy(offset = it.offset + 2) })
            )
        }
        value.solution?.let { ToolRichText(it) }
    }
}

private fun originalPollTranslation(poll: Poll): PollTranslation = PollTranslation(
    question = TextToolText(poll.question, emptyList()),
    answers = poll.answers.map { TextToolText(it.text, emptyList()) },
    solution = poll.solution.takeIf { it.isNotBlank() }?.let { TextToolText(it, emptyList()) },
)

/**
 * Proofread diffs arrive as `diff_insert`/`diff_delete`/`diff_replace` entities over the rewritten text;
 * `diff_replace` carries the replaced original in [org.monogram.core.models.TextEntity.oldText].
 */
@Composable
internal fun ToolDiffText(value: TextToolText) {
    val added = MaterialTheme.colorScheme.primary
    val removed = MaterialTheme.colorScheme.error
    val annotated = remember(value) {
        buildAnnotatedString {
            var cursor = 0
            value.entities.filter { it.kind.startsWith("diff_") }.sortedBy { it.offset }
                .forEach { entity ->
                    val start = entity.offset.coerceIn(cursor, value.text.length)
                    val end = (entity.offset + entity.length).coerceIn(start, value.text.length)
                    if (start > cursor) append(value.text.substring(cursor, start))
                    val span = value.text.substring(start, end)
                    when (entity.kind) {
                        "diff_delete" -> {
                            withStyle(
                                SpanStyle(
                                    color = removed, textDecoration = TextDecoration.LineThrough
                                )
                            ) { append(span) }
                        }

                        "diff_replace" -> {
                            entity.oldText?.takeIf { it.isNotBlank() }?.let { old ->
                                withStyle(
                                    SpanStyle(
                                        color = removed, textDecoration = TextDecoration.LineThrough
                                    )
                                ) { append(old) }
                            }
                            withStyle(SpanStyle(color = added)) { append(span) }
                        }

                        else -> {
                            withStyle(SpanStyle(color = added)) { append(span) }
                        }
                    }
                    cursor = end
                }
            if (cursor < value.text.length) append(value.text.substring(cursor))
        }
    }
    Text(annotated, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ToolProgress(onCancel: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        MonogramLoading(size = MonogramLoadingInlineSize, status = null)
        Text(
            text = stringResource(R.string.dialog_tools_working),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onCancel) { Text(stringResource(R.string.dialog_cancel)) }
    }
}

@Composable
private fun ToolResultActions(text: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            scope.launch {
                clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("", text)))
            }
        }) { Text(stringResource(R.string.dialog_copy)) }
        TextButton(onClick = {
            context.startActivity(
                android.content.Intent.createChooser(
                    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, text)
                    },
                    null,
                ),
            )
        }) { Text(stringResource(R.string.dialog_instant_view_share)) }
    }
}

@Composable
private fun TextToolsError(
    error: TelegramError,
    retry: (() -> Unit)?,
) {
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(
            stringResource(
                when {
                    error.premiumFlood -> R.string.dialog_tools_premium_quota
                    error.retryAfterSeconds != null -> R.string.dialog_tools_flood
                    error.kind == TelegramError.Kind.Network -> R.string.dialog_tools_offline
                    else -> R.string.dialog_tools_error
                },
            ),
            color = MaterialTheme.colorScheme.error,
        )
        error.retryAfterSeconds?.let { Text(stringResource(R.string.dialog_tools_retry_after, it)) }
        if (retry != null) TextButton(onClick = retry) { Text(stringResource(R.string.dialog_retry_load)) }
    }
}

@Composable
private fun PreferenceRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(
                min = 48.dp,
            )
            .toggleable(
                value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun LanguageButton(
    code: String,
    onSelect: (String) -> Unit,
) {
    var picker by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = {
        picker = true
    }) { Text(stringResource(R.string.dialog_tools_target_language, languageDisplayName(code))) }
    if (picker) {
        LanguagePicker(code, {
            onSelect(it)
            picker = false
        }, { picker = false })
    }
}

@Composable
private fun LanguagePicker(
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    LanguagePickerSheet(
        title = stringResource(R.string.dialog_tools_languages),
        searchLabel = stringResource(R.string.dialog_tools_search_language),
        emptyLabel = stringResource(R.string.dialog_tools_no_languages),
        doneLabel = stringResource(R.string.dialog_select_done),
        clearSearchLabel = stringResource(R.string.dialog_search_clear),
        selected = selected,
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}
