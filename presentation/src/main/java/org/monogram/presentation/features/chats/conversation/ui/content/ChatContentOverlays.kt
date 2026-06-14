package org.monogram.presentation.features.chats.conversation.ui.content

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import androidx.window.core.layout.WindowWidthSizeClass
import kotlinx.coroutines.launch
import org.monogram.domain.models.ChatPermissionsModel
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ConfirmationSheet
import org.monogram.presentation.core.util.LocalTabletInterfaceEnabled
import org.monogram.presentation.features.chats.conversation.ChatComponent
import org.monogram.presentation.features.chats.conversation.editor.photo.PhotoEditorScreen
import org.monogram.presentation.features.chats.conversation.editor.video.VideoEditorScreen
import org.monogram.presentation.features.chats.conversation.ui.StickerSetSheet
import org.monogram.presentation.features.chats.conversation.ui.message.BotCommandsSheet
import org.monogram.presentation.features.chats.conversation.ui.message.PollVotersSheet
import org.monogram.presentation.features.chats.conversation.ui.message.PreviewImageViewerRequest
import org.monogram.presentation.features.chats.conversation.ui.message.PreviewVideoViewerRequest
import org.monogram.presentation.features.chats.conversation.ui.pins.PinnedMessagesListSheet

@Composable
internal fun ChatContentOverlays(
    state: ChatComponent.State,
    component: ChatComponent,
    localClipboard: Clipboard,
    groupedMessages: List<GroupedMessageItem>,
    isAnyViewerOpen: Boolean,
    renderPinnedMessagesList: Boolean,
    requestPinnedMessagesListDismiss: () -> Unit,
    onPinnedSheetHidden: () -> Unit,
    onPinnedMessageClick: (MessageModel) -> Unit,
    selectedMessage: MessageModel?,
    menuOffset: Offset,
    menuMessageSize: IntSize,
    clickOffset: Offset,
    contentRect: Rect,
    canRestoreOriginalText: Boolean,
    onApplyTransformedText: (String) -> Unit,
    onRestoreOriginalText: () -> Unit,
    onDismissMessageOptions: () -> Unit,
    pendingBlockUserId: Long?,
    onRequestBlockUser: (Long) -> Unit,
    onConfirmBlockUser: (Long) -> Unit,
    onDismissBlockUser: () -> Unit,
    editingPhotoPath: String?,
    onClosePhotoEditor: () -> Unit,
    onSavePhotoEditor: (String) -> Unit,
    editingVideoPath: String?,
    onCloseVideoEditor: () -> Unit,
    onSaveVideoEditor: (String) -> Unit,
    previewImages: PreviewImageViewerRequest?,
    onDismissPreviewImages: () -> Unit,
    previewVideo: PreviewVideoViewerRequest?,
    onDismissPreviewVideo: () -> Unit,
    isCustomBackHandlingEnabled: Boolean,
    onBack: () -> Unit
) {
    val adaptiveInfo = currentWindowAdaptiveInfo()
    val isTabletInterfaceEnabled = LocalTabletInterfaceEnabled.current
    val isTablet =
        adaptiveInfo.windowSizeClass.windowWidthSizeClass == WindowWidthSizeClass.EXPANDED &&
                isTabletInterfaceEnabled
    val scope = rememberCoroutineScope()
    val rawMessageJsonUnavailable = stringResource(R.string.message_info_json_unavailable)
    var rawMessageJsonRequest by remember { mutableStateOf<MessageModel?>(null) }
    var rawMessageJson by remember { mutableStateOf<String?>(null) }
    var isRawMessageJsonLoading by remember { mutableStateOf(false) }

    if (renderPinnedMessagesList) {
        PinnedMessagesListSheet(
            isVisible = state.showPinnedMessagesList,
            allPinnedMessages = state.allPinnedMessages,
            pinnedMessageCount = state.pinnedMessageCount,
            isLoadingPinnedMessages = state.isLoadingPinnedMessages,
            isGroup = state.isGroup,
            isChannel = state.isChannel,
            fontSize = state.fontSize,
            letterSpacing = state.letterSpacing,
            bubbleRadius = state.bubbleRadius,
            stickerSize = state.stickerSize,
            autoDownloadMobile = state.autoDownloadMobile,
            autoDownloadWifi = state.autoDownloadWifi,
            autoDownloadRoaming = state.autoDownloadRoaming,
            autoDownloadFiles = state.autoDownloadFiles,
            autoplayGifs = state.autoplayGifs,
            autoplayVideos = state.autoplayVideos,
            onDismissRequest = requestPinnedMessagesListDismiss,
            onHidden = onPinnedSheetHidden,
            onMessageClick = onPinnedMessageClick,
            onUnpin = component::onUnpinMessage,
            onReplyClick = onPinnedMessageClick,
            onReactionClick = { id, reaction ->
                component.onSendReaction(id, reaction)
            },
            downloadUtils = component.downloadUtils,
            isAnyViewerOpen = isAnyViewerOpen
        )
    }

    state.selectedStickerSet?.let { stickerSet ->
        StickerSetSheet(
            stickerSet = stickerSet,
            onDismiss = component::onDismissStickerSet,
            onStickerClick = { _, path -> component.onSendSticker(path) }
        )
    }

    if (state.showPollVoters) {
        PollVotersSheet(
            voters = state.pollVoters,
            isLoading = state.isPollVotersLoading,
            onUserClick = {
                component.onDismissVoters()
                component.toProfile(it)
            },
            onDismiss = component::onDismissVoters
        )
    }

    if (state.showBotCommands) {
        BotCommandsSheet(
            commands = state.botCommands,
            onCommandClick = component::onBotCommandClick,
            onDismiss = component::onDismissBotCommands
        )
    }

    if (!isTablet) {
        ChatContentViewersInternal(
            state = state,
            component = component,
            localClipboard = localClipboard,
            previewImages = previewImages,
            onDismissPreviewImages = onDismissPreviewImages,
            previewVideo = previewVideo,
            onDismissPreviewVideo = onDismissPreviewVideo
        )
    }

    selectedMessage?.let { msg ->
        ChatMessageOptionsMenu(
            state = state,
            component = component,
            selectedMessage = msg,
            menuOffset = menuOffset,
            menuMessageSize = menuMessageSize,
            clickOffset = clickOffset,
            contentRect = contentRect,
            groupedMessages = groupedMessages,
            downloadUtils = component.downloadUtils,
            localClipboard = localClipboard,
            canRestoreOriginalText = canRestoreOriginalText,
            onApplyTransformedText = onApplyTransformedText,
            onRestoreOriginalText = onRestoreOriginalText,
            onBlockRequest = onRequestBlockUser,
            onShowRawMessageJson = { message ->
                rawMessageJsonRequest = message
                rawMessageJson = null
                isRawMessageJsonLoading = true
                scope.launch {
                    val chatId = message.chatId
                    val messageId = message.id
                    val json = runCatching {
                        component.getRawMessageJson(chatId, messageId)
                    }.getOrNull()

                    if (rawMessageJsonRequest?.chatId == chatId && rawMessageJsonRequest?.id == messageId) {
                        rawMessageJson = json ?: rawMessageJsonUnavailable
                        isRawMessageJsonLoading = false
                    }
                }
            },
            onDismiss = onDismissMessageOptions
        )
    }

    if (rawMessageJsonRequest != null) {
        RawMessageJsonSheet(
            json = rawMessageJson,
            isLoading = isRawMessageJsonLoading,
            onCopy = { json ->
                localClipboard.nativeClipboard.setPrimaryClip(
                    ClipData.newPlainText("", AnnotatedString(json))
                )
            },
            onDismiss = {
                rawMessageJsonRequest = null
                rawMessageJson = null
                isRawMessageJsonLoading = false
            }
        )
    }

    pendingBlockUserId?.let { userId ->
        ConfirmationSheet(
            icon = Icons.Rounded.Block,
            title = stringResource(R.string.block_user_title),
            description = stringResource(R.string.block_user_confirmation),
            confirmText = stringResource(R.string.action_block),
            onConfirm = { onConfirmBlockUser(userId) },
            onDismiss = onDismissBlockUser
        )
    }

    if (state.showReportDialog) {
        ReportChatDialog(
            onDismiss = component::onDismissReportDialog,
            onReasonSelected = component::onReportReasonSelected
        )
    }

    if (state.restrictUserId != null) {
        RestrictUserSheet(
            onDismiss = component::onDismissRestrictDialog,
            onConfirm = { permissions: ChatPermissionsModel, untilDate: Int ->
                component.onConfirmRestrict(permissions, untilDate)
            }
        )
    }

    editingPhotoPath?.let { path ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(20f)
        ) {
            PhotoEditorScreen(
                imagePath = path,
                onClose = onClosePhotoEditor,
                onSave = onSavePhotoEditor
            )
        }
    }

    editingVideoPath?.let { path ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(20f)
        ) {
            VideoEditorScreen(
                videoPath = path,
                onClose = onCloseVideoEditor,
                onSave = onSaveVideoEditor
            )
        }
    }

    BackHandler(enabled = isCustomBackHandlingEnabled) {
        onBack()
    }
}

