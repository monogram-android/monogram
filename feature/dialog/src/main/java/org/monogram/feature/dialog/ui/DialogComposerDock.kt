package org.monogram.feature.dialog.ui

import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.monogram.core.common.Outcome
import org.monogram.core.models.GeoPlace
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.core.models.UploadItem
import org.monogram.core.models.displayedChatAction
import org.monogram.core.models.peerAvatarCacheKey
import org.monogram.core.ui.AppearanceSettings
import org.monogram.core.ui.components.AppModalSheet
import org.monogram.core.ui.components.AppStatusBanner
import org.monogram.core.ui.components.AppSyncStatus
import org.monogram.core.ui.components.ChatWallpaper
import org.monogram.core.ui.components.MessageListSkeleton
import org.monogram.core.ui.components.OnlineLeaseExpiry
import org.monogram.core.ui.components.PeerAvatar
import org.monogram.core.ui.components.SearchField
import org.monogram.core.ui.components.TypingDots
import org.monogram.core.ui.components.UnreadBadge
import org.monogram.core.ui.components.listItemMotion
import org.monogram.core.ui.components.liveActionTransition
import org.monogram.core.ui.components.peerStatusLabel
import org.monogram.core.ui.components.rememberPeerStatusNow
import org.monogram.core.ui.components.typingStatusText
import org.monogram.core.ui.components.uiLabel
import org.monogram.core.ui.loading.MonogramLoading
import org.monogram.core.ui.loading.MonogramLoadingListSize
import org.monogram.core.ui.menu.AppMenuPlacementState
import org.monogram.core.ui.menu.AppMenuScrimPopup
import org.monogram.core.ui.rememberEnsuredFile
import org.monogram.feature.dialog.ComposerAt
import org.monogram.feature.dialog.ComposerPanels
import org.monogram.feature.dialog.DialogComponent
import org.monogram.feature.dialog.DialogDayKind
import org.monogram.feature.dialog.DialogStore
import org.monogram.feature.dialog.DialogTime
import org.monogram.feature.dialog.R
import org.monogram.feature.dialog.albumSlice
import org.monogram.feature.dialog.dialogSyncStatus
import org.monogram.feature.dialog.followBottomFromScroll
import org.monogram.feature.dialog.historyPagingAllowed
import org.monogram.feature.dialog.isAlbumHead
import org.monogram.feature.dialog.liftAnchorTarget
import org.monogram.feature.dialog.shouldFollowIncomingNewest
import org.monogram.feature.dialog.shouldPageNewer
import org.monogram.feature.dialog.shouldPageOlder
import org.monogram.feature.dialog.unreadDividerIndex
import org.monogram.network.http.MediaPriority
import java.io.File
import java.time.ZoneId
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import org.monogram.core.ui.components.SponsorBadge

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DialogComposerDock(
    component: DialogComponent,
    composer: androidx.compose.runtime.MutableState<TextFieldValue>,
    draft: String,
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
    botKeyboard: org.monogram.core.models.ReplyMarkup? = null,
    botKeyboardMessageId: Int = 0,
    botPlaceholder: String? = null,
) {
    var value by composer
    val context = LocalContext.current
    val editorSlot by component.editorSlot.subscribeAsState()
    var editorValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    var autoEditorPending by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    var selectionMenuRequested by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    var pasteMedia by remember { mutableStateOf(emptyList<Uri>()) }
    LaunchedEffect(clipboard, selectionMenuRequested) {
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
    LaunchedEffect(value.text, value.composition, autoEditorPending) {
        if (autoEditorPending && value.composition == null && needsFullScreenEditor(value.text)) {
            delay(400)
            editorValue = value.copy(composition = null)
            autoEditorPending = false
            component.openMarkdownEditor()
        }
    }
    LaunchedEffect(draft) {
        if (draft != value.text) {
            value = TextFieldValue(draft, TextRange(draft.length))
        }
    }
    LaunchedEffect(value.text) {
        if (value.text == draft) return@LaunchedEffect
        delay(400)
        component.onDraftChanged(value.text)
    }
    if (!canView) {
        RestrictionBar(text = stringResource(R.string.dialog_cant_view))
        return
    }
    androidx.activity.compose.BackHandler(enabled = hasComposerSelection) {
        value = collapseComposerSelection(value)
    }
    Column {
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
                val slice = selectedComposerPlain(value)
                if (slice.isNotBlank()) clipboardScope.launch {
                    clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("text", slice)))
                }
            },
            onCut = {
                val slice = selectedComposerPlain(value)
                if (slice.isNotBlank()) clipboardScope.launch {
                    clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("text", slice)))
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
            onWrap = { left, right ->
                value = wrapMarkdown(value, left, right)
                component.onDraftChanged(value.text)
            },
            onQuote = {
                value = wrapQuote(value)
                component.onDraftChanged(value.text)
            },
        )
        DialogWriteBar(
            composer = value,
            onComposerChange = {
                val expandEditor = it.composition == null && needsFullScreenEditor(it.text) &&
                    (value.composition != null || !needsFullScreenEditor(value.text))
                value = it
                if (ComposerAt.mentionToken(it.text) != null) {
                    component.onDraftChanged(it.text)
                }
                if (expandEditor) {
                    autoEditorPending = true
                }
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
            onSend = {
                val sent = value.text
                if (!editing) {
                    value = TextFieldValue("")
                    autoEditorPending = false
                }
                component.onSend(sent)
            },
            onAttach = component::onToggleAttachSheet,
            onEmoji = component::onToggleEmojiPanel,
            onRetryFailed = component::onRetryFailed,
            onCancelEdit = component::onCancelEdit,
            onClearReply = component::onClearReply,
            onClearAttach = component::onClearAttach,
            onReceiveMedia = receiveMedia,
            hint = botPlaceholder,
            onOpenEditor = {
                autoEditorPending = false
                editorValue = value.copy(composition = null)
                component.openMarkdownEditor()
            },
            onSelectionMenuVisibilityChange = { selectionMenuRequested = it },
        )
        }
    }
    val editorTransition = remember { MutableTransitionState(false) }
    editorTransition.targetState = editorSlot.child != null
    if (editorTransition.currentState || editorTransition.targetState) {
        Dialog(
            onDismissRequest = component::closeMarkdownEditor,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            AnimatedVisibility(
                visibleState = editorTransition,
                enter = fadeIn() + slideInVertically { it / 12 },
                exit = fadeOut() + slideOutVertically { it / 12 },
            ) {
                MarkdownEditorScreen(
                    value = editorValue,
                    onValueChange = { editorValue = it },
                    onApply = {
                        value = finishMarkdownEditor(value, editorValue, apply = true)
                        component.onDraftChanged(value.text)
                        component.closeMarkdownEditor()
                    },
                    onDismiss = component::closeMarkdownEditor,
                )
            }
        }
    }
}
