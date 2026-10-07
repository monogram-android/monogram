package org.monogram.feature.dialog.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.monogram.core.models.ReplyMarkupKind
import org.monogram.core.models.ReplyMarkups
import org.monogram.core.ui.media.MediaPlaybackHolder
import org.monogram.core.ui.media.MediaSurface
import org.monogram.core.ui.media.MessagePlaybackBar
import org.monogram.feature.dialog.DialogComponent
import org.monogram.feature.dialog.DialogStore
import org.monogram.feature.dialog.R

@Composable
internal fun DialogScreenDialogs(
    component: DialogComponent,
    state: DialogStore.State,
    composer: MutableState<TextFieldValue>,
    packDocumentId: MutableState<Long?>,
    instantViewUrl: MutableState<String?>,
    instantViewHash: androidx.compose.runtime.MutableIntState,
    pendingDeleteIds: MutableState<List<Int>>,
    taskDraftFor: MutableState<Pair<Int, Int>?>,
    taskDraft: MutableState<String>,
    onPhotos: () -> Unit,
    onFile: () -> Unit,
    onLocation: () -> Unit,
    onCloseAttachAnimated: () -> Unit,
) {
    var packDocumentId by packDocumentId
    var instantViewUrl by instantViewUrl
    var instantViewHash by instantViewHash
    var pendingDeleteIds by pendingDeleteIds
    var taskDraftFor by taskDraftFor
    var taskDraft by taskDraft
    val playbackContext = LocalContext.current
    val playbackSession = remember(playbackContext) { MediaPlaybackHolder.session(playbackContext) }
    val playbackVisible =
        playbackSession.isMessagePlayback && playbackSession.surface != MediaSurface.VIEWER && playbackSession.surface != MediaSurface.PIP
    val pendingDelete = pendingDeleteIds.mapNotNull { id -> state.messages.firstOrNull { it.id.id == id } }
    AnimatedVisibility(
        visible = playbackVisible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    ) {
        MessagePlaybackBar(
            session = playbackSession,
            onOpenMessage = {
                component.onJumpToMessage(
                    playbackSession.current?.sourceMessageId ?: 0
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    DialogMiniPlayer()
    DialogComposerDock(
        component = component,
        composer = composer,
        draft = state.draft,
        draftFormatting = state.draftFormatting,
        draftEntities = state.draftEntities,
        draftPreview = state.draftPreview,
        draftHasMarkdown = state.draftHasMarkdown,
        sending = state.sending,
        canView = state.canView,
        canSendPlain = state.canSendPlain,
        canSendPhotos = state.canSendPhotos,
        isChannel = state.isChannel,
        editing = state.editing != null,
        editingBody = state.editing?.text.orEmpty(),
        replyBody = state.replyTo?.let {
            it.text ?: replyMediaFallbackLabel(it.mediaKind)
        },
        pendingAttach = state.pendingAttach,
        hasFailed = state.messages.any { it.failed },
        composerFocusSeq = state.composerFocusSeq,
        linkPreview = state.linkPreview,
        linkPreviewLoading = state.linkPreviewLoading,
        linkPreviewHidden = state.linkPreviewHidden,
        linkPreviewUrls = state.linkPreviewUrls,
        linkPreviewChoice = state.linkPreviewChoice,
        onSelectLinkPreview = component::onSelectLinkPreview,
        onDismissLinkPreview = component::onDismissLinkPreview,
        onRestoreLinkPreview = component::onRestoreLinkPreview,
        onPhotos = onPhotos,
        onFile = onFile,
        onLocation = onLocation,
        onCloseAttachAnimated = onCloseAttachAnimated,
        composerPanel = state.composerPanel,
        emojiTab = state.emojiTab,
        botKeyboard = state.let { current ->
            val latest = ReplyMarkups.latestBotKeyboard(current.messages)
            val key = ReplyMarkups.serialize(latest)
            latest.takeIf {
                it != null &&
                        it.kind == ReplyMarkupKind.Keyboard &&
                        it.rows.isNotEmpty() &&
                        key != current.keyboardDismissedKey
            }
        },
        botKeyboardMessageId = ReplyMarkups
            .latestBotKeyboardMessage(state.messages)
            ?.takeIf { it.replyMarkup?.kind == ReplyMarkupKind.Keyboard }
            ?.id?.id ?: 0,
        botPlaceholder = ReplyMarkups.latestBotKeyboard(state.messages)
            ?.takeIf {
                it.kind == ReplyMarkupKind.ForceReply ||
                        it.kind == ReplyMarkupKind.Keyboard
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
    val deleteOfferNow = if (pendingDelete.size == pendingDeleteIds.size && pendingDelete.isNotEmpty()) {
        deleteOffer(state, pendingDelete, System.currentTimeMillis() / 1000)
    } else {
        DeleteOffer(forMe = false, forEveryone = false)
    }
    LaunchedEffect(pendingDeleteIds, pendingDelete.size, deleteOfferNow.visible) {
        if (pendingDeleteIds.isNotEmpty() &&
            (pendingDelete.size != pendingDeleteIds.size || !deleteOfferNow.visible)
        ) {
            pendingDeleteIds = emptyList()
        }
    }
    if (deleteOfferNow.visible) {
        DeleteMessagesDialog(
            count = pendingDelete.size,
            offer = deleteOfferNow,
            onDismiss = { pendingDeleteIds = emptyList() },
            onConfirm = { forEveryone ->
                val revoke = deleteRevoke(forEveryone)
                pendingDelete.forEach { component.onDelete(it.id.id, revoke) }
                pendingDeleteIds = emptyList()
            },
        )
    }
    instantViewUrl?.let { ivUrl ->
        Dialog(
            onDismissRequest = { instantViewUrl = null },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            ),
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

@Composable
internal fun DeleteMessagesDialog(
    count: Int,
    offer: DeleteOffer,
    onDismiss: () -> Unit,
    onConfirm: (forEveryone: Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (count == 1) {
                    stringResource(R.string.dialog_delete_confirm)
                } else {
                    pluralStringResource(R.plurals.dialog_delete_confirm_count, count, count)
                },
            )
        },
        confirmButton = {
            Column {
                if (offer.forEveryone) {
                    TextButton(onClick = { onConfirm(true) }) {
                        Text(stringResource(R.string.dialog_delete_for_everyone))
                    }
                }
                if (offer.forMe) {
                    TextButton(onClick = { onConfirm(false) }) {
                        Text(stringResource(R.string.dialog_delete_for_me))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}
