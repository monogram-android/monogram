package org.monogram.feature.dialog.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.monogram.core.models.ReplyMarkup
import org.monogram.core.models.StyledText
import org.monogram.core.models.TextEntities
import org.monogram.core.models.TextEntity
import org.monogram.core.models.UploadItem
import org.monogram.core.models.WebpagePreview
import org.monogram.core.models.remapTextEntities
import org.monogram.core.models.toggleComposerEntity
import org.monogram.feature.dialog.ComposerPanels
import org.monogram.feature.dialog.DialogComponent
import org.monogram.feature.dialog.R
import org.monogram.feature.dialog.performComposerSlot

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DialogComposerDock(
    component: DialogComponent,
    composer: MutableState<TextFieldValue>,
    draft: String,
    draftFormatting: Boolean,
    draftEntities: List<TextEntity>,
    draftPreview: StyledText?,
    draftHasMarkdown: Boolean,
    sending: Boolean,
    canView: Boolean,
    canSendPlain: Boolean,
    canSendPhotos: Boolean,
    isChannel: Boolean,
    editing: Boolean,
    editingBody: String,
    replyBody: String?,
    pendingAttach: List<UploadItem>,
    hasFailed: Boolean,
    botKeyboard: ReplyMarkup? = null,
    botKeyboardMessageId: Int = 0,
    botPlaceholder: String? = null,
    composerFocusSeq: Int = 0,
    linkPreview: WebpagePreview? = null,
    linkPreviewLoading: Boolean = false,
    linkPreviewHidden: Boolean = false,
    linkPreviewUrls: List<String> = emptyList(),
    linkPreviewChoice: String? = null,
    onSelectLinkPreview: (String) -> Unit = {},
    onDismissLinkPreview: () -> Unit = {},
    onRestoreLinkPreview: () -> Unit = {},
    onPhotos: () -> Unit = {},
    onFile: () -> Unit = {},
    onLocation: () -> Unit = {},
    onCloseAttachAnimated: () -> Unit = {},
    composerPanel: String? = null,
    emojiTab: String = "",
) {
    var value by composer
    val context = LocalContext.current
    val editorSlot by component.editorSlot.subscribeAsState()
    var editorMarkdown by rememberSaveable { mutableStateOf(false) }
    var editorInitialized by rememberSaveable { mutableStateOf(false) }
    var editorEntities by rememberSaveable(
        stateSaver = Saver<List<TextEntity>, String>(
            save = { TextEntities.serialize(it) ?: "[]" },
            restore = { TextEntities.parse(it) },
        )
    ) { mutableStateOf(emptyList()) }
    var editorValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    LaunchedEffect(editorSlot.child) {
        if (editorSlot.child == null) editorInitialized = false
        else if (!editorInitialized) {
            editorInitialized = true
            editorValue = value
            editorEntities = draftEntities
            editorMarkdown = draftFormatting && component.markdownAvailable
        }
    }
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    var selectionMenuRequested by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    var pasteMedia by remember { mutableStateOf(emptyList<Uri>()) }
    LaunchedEffect(clipboard, selectionMenuRequested) {
        if (!selectionMenuRequested) {
            pasteText = ""
            pasteMedia = emptyList()
            return@LaunchedEffect
        }
        val clip = clipboard.getClipEntry()?.clipData
        pasteText = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
        pasteMedia = buildList {
            if (clip == null) return@buildList
            for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add)
        }.distinct()
    }
    val receiveMedia: (List<Uri>, TransferableContent?) -> Unit = { uris, transferableContent ->
        val imageUris = uris.asSequence()
            .distinct()
            .filter { uri ->
                runCatching {
                    context.contentResolver.getType(uri)?.startsWith("image/") == true
                }.getOrDefault(false)
            }
            .take(10)
            .toList()
        if (!editing && canSendPhotos && imageUris.isNotEmpty()) {
            clipboardScope.launch {
                try {
                    val picked = imageUris.mapNotNull { uri ->
                        try {
                            copyPickedMedia(
                                context = context,
                                uri = uri,
                                kind = "photo",
                                fallbackExt = "jpg",
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (picked.isNotEmpty()) component.onAppendMedia(picked.map(PickedMedia::toUploadItem))
                } finally {
                    transferableContent?.toString()
                }
            }
        }
    }
    val hasComposerSelection = value.selection.length > 0
    LaunchedEffect(draft) {
        if (draft != value.text) {
            value = TextFieldValue(draft, TextRange(draft.length))
        }
    }
    LaunchedEffect(value.text) {
        if (value.text == draft) return@LaunchedEffect
        delay(100)
        component.onDraftChanged(value.text)
    }
    if (!canView) {
        RestrictionBar(text = stringResource(R.string.dialog_cant_view))
        return
    }
    androidx.activity.compose.BackHandler(enabled = hasComposerSelection) {
        value = collapseComposerSelection(value)
    }
    val composerChrome = LocalComposerChrome.current
    val composerDensity = LocalDensity.current
    Column(
        modifier = Modifier
            .onSizeChanged { size ->
                composerChrome.value = with(composerDensity) { size.height.toDp() }
            }
            .blockWriteBarChatSwipe(),
    ) {
        botKeyboard?.let { keyboard ->
            BotKeyboardGrid(
                markup = keyboard,
                onClick = { button ->
                    component.onBotButton(botKeyboardMessageId, button, fromKeyboard = true)
                },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                compact = false,
            )
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            ComposerSelectionMenu(
                visible = hasComposerSelection && selectionMenuRequested,
                onDismiss = {
                    selectionMenuRequested = false
                },
                canPaste = pasteText.isNotEmpty() || pasteMedia.isNotEmpty(),
                onCopy = {
                    val slice = value.text.substring(value.selection.min, value.selection.max)
                    if (slice.isNotBlank()) clipboardScope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(
                                android.content.ClipData.newPlainText(
                                    "text",
                                    slice
                                )
                            )
                        )
                    }
                },
                onCut = {
                    val slice = value.text.substring(value.selection.min, value.selection.max)
                    if (slice.isNotBlank()) clipboardScope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(
                                android.content.ClipData.newPlainText(
                                    "text",
                                    slice
                                )
                            )
                        )
                    }
                    value = deleteComposerSelection(value)
                    component.onDraftChanged(value.text)
                },
                onPaste = {
                    if (pasteText.isNotEmpty()) {
                        val lo = value.selection.min
                        val hi = value.selection.max
                        val next = value.text.substring(0, lo) + pasteText +
                                value.text.substring(hi)
                        value = TextFieldValue(
                            text = next,
                            selection = TextRange(lo + pasteText.length),
                        )
                        component.onDraftChanged(value.text)
                    }
                    receiveMedia(pasteMedia, null)
                },
                onSelectAll = {
                    value = selectAllComposer(value)
                },
                onWrap = { left, _ ->
                    if (left == "[") {
                        selectionMenuRequested = false
                        component.openMarkdownEditor()
                    } else {
                        val kind = when (left) {
                            "**" -> "bold"; "__" -> "italic"; "~~" -> "strike"; "||" -> "spoiler"; else -> "code"
                        }
                        component.onDraftEntities(
                            toggleComposerEntity(
                                value.text,
                                draftEntities,
                                value.selection.min,
                                value.selection.max,
                                kind
                            )
                        )
                    }
                },
                onUnderline = {
                    component.onDraftChanged(value.text)
                    component.onDraftEntities(
                        toggleComposerEntity(
                            value.text,
                            draftEntities,
                            value.selection.min,
                            value.selection.max,
                            "underline"
                        )
                    )
                },
                onEditor = {
                    selectionMenuRequested = false
                    component.openMarkdownEditor()
                },
                onQuote = {
                    component.onDraftEntities(
                        toggleComposerEntity(
                            value.text,
                            draftEntities,
                            value.selection.min,
                            value.selection.max,
                            "blockquote"
                        )
                    )
                },
            )
            DialogWriteBar(
                composer = value,
                draftFormatting = draftFormatting,
                markdownAvailable = component.markdownAvailable,
                onOpenEditor = component::openMarkdownEditor,
                draftPreview = draftPreview?.takeIf { value.text == draft },
                draftHasMarkdown = draftHasMarkdown,
                draftHasEntities = draftEntities.any { it.kind != "custom_emoji" },
                draftEntities = draftEntities,
                onDraftFormatting = component::onDraftFormatting,
                onComposerChange = {
                    value = it
                    component.onDraftChanged(it.text)
                },
                sending = sending,
                canSendPlain = canSendPlain,
                canSendPhotos = canSendPhotos,
                isChannel = isChannel,
                editing = editing,
                editingBody = editingBody,
                replyBody = replyBody,
                pendingAttach = pendingAttach,
                hasFailed = hasFailed,
                composerFocusSeq = composerFocusSeq,
                linkPreview = linkPreview,
                linkPreviewLoading = linkPreviewLoading,
                linkPreviewHidden = linkPreviewHidden,
                linkPreviewUrls = linkPreviewUrls,
                linkPreviewChoice = linkPreviewChoice,
                onSelectLinkPreview = onSelectLinkPreview,
                onDismissLinkPreview = onDismissLinkPreview,
                onRestoreLinkPreview = onRestoreLinkPreview,
                panelOpen = composerPanel != null,
                onClosePanel = {
                    when (composerPanel) {
                        ComposerPanels.ATTACH -> onCloseAttachAnimated()
                        ComposerPanels.EMOJI -> component.onToggleEmojiPanel()
                    }
                },
                onSend = {
                    val sent = value.text
                    component.onSend(sent)
                },
                onSlot = { action ->
                    performComposerSlot(
                        action = action,
                        panel = composerPanel,
                        tab = emojiTab,
                        closeEmoji = component::onToggleEmojiPanel,
                        closeAttach = component::onCloseAttachSheet,
                        openEmojiTab = component::onSetEmojiTab,
                        openAttach = component::onToggleAttachSheet,
                        openPhotos = onPhotos,
                        openFile = onFile,
                        openLocation = onLocation,
                    )
                },
                onRetryFailed = component::onRetryFailed,
                onCancelEdit = component::onCancelEdit,
                onClearReply = component::onClearReply,
                onClearAttach = component::onClearAttach,
                onReceiveMedia = receiveMedia,
                hint = botPlaceholder,
                onSelectionMenuVisibilityChange = { selectionMenuRequested = it },
            )
        }
    }
    val editorTransition = remember { MutableTransitionState(false) }
    editorTransition.targetState = editorSlot.child != null
    if (editorTransition.currentState || editorTransition.targetState) {
        Dialog(
            onDismissRequest = component::closeMarkdownEditor,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            ),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visibleState = editorTransition,
                    modifier = Modifier.fillMaxSize(),
                    enter = fadeIn() + slideInVertically { it / 12 },
                    exit = fadeOut() + slideOutVertically { it / 12 },
                ) {
                    MarkdownEditorScreen(
                        value = editorValue,
                        entities = editorEntities,
                        markdownAvailable = component.markdownAvailable,
                        markdownEnabled = editorMarkdown,
                        onMarkdownEnabled = { editorMarkdown = it },
                        preparePreview = component::prepareEditorPayload,
                        onEntitiesChange = { editorEntities = it },
                        onValueChange = {
                            editorEntities = remapTextEntities(
                                editorValue.text,
                                it.text,
                                editorEntities
                            )
                            editorValue = it
                        },
                        onApply = {
                            value = finishMarkdownEditor(value, editorValue, apply = true)
                            component.onDraftChanged(value.text)
                            component.onDraftEntities(editorEntities)
                            component.onDraftFormatting(editorMarkdown)
                            component.closeMarkdownEditor()
                        },
                        onEmoji = {
                            value = editorValue
                            component.onDraftChanged(value.text)
                            component.onDraftEntities(editorEntities)
                            component.onDraftFormatting(editorMarkdown)
                            component.closeMarkdownEditor()
                            component.onToggleEmojiPanel()
                        },
                        onDismiss = component::closeMarkdownEditor,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
