package org.monogram.feature.dialog.ui

import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
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

@Composable
internal fun DialogScreenDialogs(
    component: DialogComponent,
    state: DialogStore.State,
    composer: androidx.compose.runtime.MutableState<TextFieldValue>,
    packDocumentId: androidx.compose.runtime.MutableState<Long?>,
    instantViewUrl: androidx.compose.runtime.MutableState<String?>,
    instantViewHash: androidx.compose.runtime.MutableIntState,
    pendingDeleteId: androidx.compose.runtime.MutableState<Int?>,
    taskDraftFor: androidx.compose.runtime.MutableState<Pair<Int, Int>?>,
    taskDraft: androidx.compose.runtime.MutableState<String>,
) {
    var packDocumentId by packDocumentId
    var instantViewUrl by instantViewUrl
    var instantViewHash by instantViewHash
    var pendingDeleteId by pendingDeleteId
    var taskDraftFor by taskDraftFor
    var taskDraft by taskDraft
    val pendingDelete = state.messages.firstOrNull { it.id.id == pendingDeleteId }
        DialogMiniPlayer()
        DialogComposerDock(
            component = component,
            composer = composer,
            draft = state.draft,
            sending = state.sending,
            canView = state.canView,
            canSendPlain = state.canSendPlain,
            canSendPhotos = state.canSendPhotos,
            isChannel = state.isChannel,
            editing = state.editing != null,
            editingBody = state.editing?.text.orEmpty(),
            replyBody = state.replyTo?.let {
                it.text ?: stringResource(R.string.dialog_media_photo)
            },
            pendingAttach = state.pendingAttach,
            hasFailed = state.messages.any { it.failed },
            botKeyboard = state.let { current ->
                val latest = org.monogram.core.models.ReplyMarkups.latestBotKeyboard(current.messages)
                val key = org.monogram.core.models.ReplyMarkups.serialize(latest)
                latest.takeIf {
                    it != null &&
                        it.kind == org.monogram.core.models.ReplyMarkupKind.Keyboard &&
                        it.rows.isNotEmpty() &&
                        key != current.keyboardDismissedKey
                }
            },
            botKeyboardMessageId = org.monogram.core.models.ReplyMarkups
                .latestBotKeyboardMessage(state.messages)
                ?.takeIf { it.replyMarkup?.kind == org.monogram.core.models.ReplyMarkupKind.Keyboard }
                ?.id?.id ?: 0,
            botPlaceholder = org.monogram.core.models.ReplyMarkups.latestBotKeyboard(state.messages)
                ?.takeIf {
                    it.kind == org.monogram.core.models.ReplyMarkupKind.ForceReply ||
                        it.kind == org.monogram.core.models.ReplyMarkupKind.Keyboard
                }?.placeholder,
        )
        if (state.forwardMessage != null) {
            ForwardPickerSheet(
                targets = state.forwardTargets,
                query = state.forwardQuery,
                forwarding = state.forwarding,
                mediaRepository = component.mediaRepository,
                onQuery = component::onForwardQuery,
                onSelect = component::onForwardTo,
                onDismiss = component::onClearForward,
            )
        }
        packDocumentId?.let { documentId ->
            CompositionLocalProvider(
                LocalDialogMedia provides component.mediaRepository,
            ) {
                StickerPackSheet(
                    documentId = documentId,
                    client = component.client,
                    onDismiss = { packDocumentId = null },
                )
            }
        }
        if (state.botAlert && !state.botNotice.isNullOrBlank()) {
            AlertDialog(
                onDismissRequest = component::onDismissBotAlert,
                text = { Text(state.botNotice.orEmpty()) },
                confirmButton = {
                    TextButton(onClick = component::onDismissBotAlert) {
                        Text(stringResource(R.string.dialog_bot_ok))
                    }
                },
            )
        }
        taskDraftFor?.let { (messageId, nextId) ->
            AlertDialog(
                onDismissRequest = { taskDraftFor = null; taskDraft = "" },
                title = { Text(stringResource(R.string.dialog_checklist_add)) },
                text = {
                    OutlinedTextField(
                        value = taskDraft,
                        onValueChange = { taskDraft = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.dialog_checklist_add_hint)) },
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val title = taskDraft.trim()
                            if (title.isNotEmpty()) {
                                component.onAppendChecklistItems(messageId, nextId, listOf(title))
                            }
                            taskDraftFor = null
                            taskDraft = ""
                        },
                        enabled = taskDraft.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.dialog_checklist_add_action))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { taskDraftFor = null; taskDraft = "" }) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                },
            )
        }
        pendingDelete?.let { deleting ->
            AlertDialog(
                onDismissRequest = { pendingDeleteId = null },
                title = { Text(stringResource(R.string.dialog_delete_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            component.onDelete(deleting.id.id, revoke = true)
                            pendingDeleteId = null
                        },
                    ) {
                        Text(stringResource(R.string.dialog_delete_for_everyone))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteId = null }) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                },
            )
        }
        instantViewUrl?.let { ivUrl ->
            Dialog(
                onDismissRequest = { instantViewUrl = null },
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) {
                InstantViewScreen(
                    controller = component.instantViewController(ivUrl, instantViewHash),
                    mediaRepository = component.mediaRepository,
                    onDismiss = { instantViewUrl = null },
                    onOpenPeer = component::onOpenPeer,
                )
            }
        }
}
