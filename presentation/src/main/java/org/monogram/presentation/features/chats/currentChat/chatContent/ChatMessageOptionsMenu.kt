package org.monogram.presentation.features.chats.currentChat.chatContent

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.monogram.domain.models.ChatPermissionsModel
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageViewerModel
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.currentChat.ChatComponent
import org.monogram.presentation.features.stickers.ui.menu.MessageOptionsMenu

@Composable
fun ChatMessageOptionsMenu(
    state: ChatComponent.State,
    component: ChatComponent,
    selectedMessage: MessageModel,
    menuOffset: Offset,
    menuMessageSize: IntSize,
    clickOffset: Offset,
    contentRect: Rect,
    groupedMessages: List<GroupedMessageItem>,
    downloadUtils: IDownloadUtils,
    clipboardManager: ClipboardManager,
    onDismiss: () -> Unit
) {
    val canCheckViewersList = remember(state.isChannel, state.isGroup, state.memberCount) {
        !state.isChannel && (!state.isGroup || state.memberCount in 1 until 100)
    }
    val shouldShowViewsInfo = remember(state.isChannel, selectedMessage.forwardInfo, selectedMessage.canGetViewers) {
        state.isChannel && selectedMessage.forwardInfo == null && selectedMessage.canGetViewers
    }

    val index = groupedMessages.indexOfFirst { item ->
        when (item) {
            is GroupedMessageItem.Single -> item.message.id == selectedMessage.id
            is GroupedMessageItem.Album -> item.messages.any { it.id == selectedMessage.id }
        }
    }
    val olderMsg = if (index != -1) {
        when (val olderItem = groupedMessages.getOrNull(index + 1)) {
            is GroupedMessageItem.Single -> olderItem.message
            is GroupedMessageItem.Album -> olderItem.messages.last()
            null -> null
        }
    } else null
    val newerMsg = if (index != -1) {
        when (val newerItem = groupedMessages.getOrNull(index - 1)) {
            is GroupedMessageItem.Single -> newerItem.message
            is GroupedMessageItem.Album -> newerItem.messages.first()
            null -> null
        }
    } else null

    var messageWithReadDate by remember(selectedMessage) { mutableStateOf(selectedMessage) }
    var messageViewers by remember(selectedMessage) { mutableStateOf<List<MessageViewerModel>>(emptyList()) }
    var isLoadingViewers by remember(selectedMessage) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(selectedMessage, canCheckViewersList) {
        if (canCheckViewersList &&
            selectedMessage.isOutgoing &&
            selectedMessage.canGetReadReceipts &&
            selectedMessage.readDate == 0
        ) {
            scope.launch {
                val readDate = component.getMessageReadDate(selectedMessage.chatId, selectedMessage.id, selectedMessage.date)
                if (readDate > 0) {
                    messageWithReadDate = selectedMessage.copy(readDate = readDate)
                }
            }
        }
    }

    val canShowViewersList = remember(state.memberCount, selectedMessage) {
        state.memberCount in 1 until 100 &&
                selectedMessage.isOutgoing &&
                selectedMessage.forwardInfo == null &&
                (selectedMessage.canGetReadReceipts || selectedMessage.canGetViewers)
    }

    suspend fun reloadViewers() {
        if (!canShowViewersList) return
        isLoadingViewers = true
        messageViewers = component
            .getMessageViewers(selectedMessage.chatId, selectedMessage.id)
            .sortedByDescending { it.viewedDate }
        isLoadingViewers = false
    }

    LaunchedEffect(selectedMessage, canShowViewersList) {
        if (canShowViewersList) {
            reloadViewers()
        }
    }

    val density = LocalDensity.current

    val nextMsgText = (newerMsg?.content as? MessageContent.Text)?.text
    val currentCaption = when (val c = selectedMessage.content) {
        is MessageContent.Photo -> c.caption
        is MessageContent.Video -> c.caption
        is MessageContent.Gif -> c.caption
        else -> null
    }
    val isCaptionSameAsNext = currentCaption != null && currentCaption.isNotEmpty() && currentCaption == nextMsgText
    val shouldShowSeparatePost = currentCaption != null && currentCaption.isNotEmpty() && !isCaptionSameAsNext

    val splitOffset = remember(selectedMessage, menuMessageSize, density, shouldShowSeparatePost) {
        if (!shouldShowSeparatePost) return@remember null

        val isChannel = state.isChannel && state.currentTopicId == null
        val width = menuMessageSize.width.toFloat()

        when (val content = selectedMessage.content) {
            is MessageContent.Photo -> {
                val ratio = if (content.width > 0 && content.height > 0)
                    (content.width.toFloat() / content.height.toFloat()).let {
                        if (isChannel) it.coerceIn(0.6f, 1.8f) else it.coerceIn(0.5f, 2f)
                    }
                else 1f

                val height = width / ratio
                val maxHeight = with(density) {
                    if (isChannel) 450.dp.toPx() else 320.dp.toPx()
                }
                val minHeight = with(density) {
                    if (isChannel) 130.dp.toPx() else 0f
                }

                height.coerceIn(minHeight, maxHeight).toInt()
            }

            is MessageContent.Video -> {
                val ratio = if (content.width > 0 && content.height > 0)
                    (content.width.toFloat() / content.height.toFloat()).let {
                        if (isChannel) it.coerceIn(0.6f, 1.8f) else it.coerceIn(0.5f, 2f)
                    }
                else 1f

                val height = width / ratio
                val maxHeight = with(density) {
                    if (isChannel) 420.dp.toPx() else 500.dp.toPx()
                }
                val minHeight = with(density) {
                    if (isChannel) 130.dp.toPx() else 0f
                }

                height.coerceIn(minHeight, maxHeight).toInt()
            }

            is MessageContent.Gif -> {
                val ratio = if (content.width > 0 && content.height > 0)
                    (content.width.toFloat() / content.height.toFloat()).let {
                        if (isChannel) it else it.coerceIn(0.5f, 2f)
                    }
                else 1f

                val height = width / ratio
                val maxHeight = with(density) {
                    if (isChannel) 600.dp.toPx() else 400.dp.toPx()
                }
                val minHeight = with(density) {
                    if (isChannel) 0f else 160.dp.toPx()
                }

                height.coerceIn(minHeight, maxHeight).toInt()
            }

            else -> null
        }
    }

    val senderIsUser = selectedMessage.senderId > 0L
    val canModerateInChat = (state.isGroup || state.isChannel) && state.isAdmin
    val canBlockUser = !selectedMessage.isOutgoing && senderIsUser &&
            (canModerateInChat || (!state.isGroup && !state.isChannel))
    val canRestrictUser = canBlockUser && (state.isGroup || state.isChannel) && state.isAdmin
    val isOtherUserDialog = state.otherUser?.id?.let { it != state.currentUser?.id } == true
    val canReportMessage = !selectedMessage.isOutgoing && (
            state.isGroup || state.isChannel ||
                    isOtherUserDialog
            )
    val canCopyLink = state.isGroup || state.isChannel
    val canPinMessages = state.isAdmin || state.permissions.canPinMessages

    MessageOptionsMenu(
        message = messageWithReadDate,
        canWrite = state.canWrite,
        canPinMessages = canPinMessages,
        isPinned = selectedMessage.id == state.pinnedMessage?.id,
        messageOffset = menuOffset,
        messageSize = menuMessageSize,
        clickOffset = clickOffset,
        contentRect = contentRect,
        isSameSenderAbove = olderMsg?.senderId == selectedMessage.senderId && !shouldShowDate(
            selectedMessage,
            olderMsg
        ),
        isSameSenderBelow = newerMsg != null && newerMsg.senderId == selectedMessage.senderId && !shouldShowDate(
            newerMsg,
            selectedMessage
        ),
        showReadInfo = canCheckViewersList,
        showViewsInfo = shouldShowViewsInfo,
        showViewersList = canShowViewersList,
        canReport = canReportMessage,
        canBlock = canBlockUser,
        canRestrict = canRestrictUser,
        canCopyLink = canCopyLink,
        viewers = messageViewers,
        isLoadingViewers = isLoadingViewers,
        onReloadViewers = {
            scope.launch { reloadViewers() }
        },
        onViewerClick = { component.toProfile(it) },
        videoPlayerPool = component.videoPlayerPool,
        bubbleRadius = state.bubbleRadius,
        splitOffset = splitOffset,
        onReply = {
            component.onReplyMessage(selectedMessage)
            onDismiss()
        },
        onPin = {
            if (selectedMessage.id == state.pinnedMessage?.id) component.onUnpinMessage(selectedMessage) else component.onPinMessage(
                selectedMessage
            )
            onDismiss()
        },
        onEdit = {
            component.onEditMessage(selectedMessage)
            onDismiss()
        },
        onDelete = { revoke ->
            component.onDeleteMessage(selectedMessage, revoke)
            onDismiss()
        },
        onForward = {
            component.onForwardMessage(selectedMessage)
            onDismiss()
        },
        onSelect = {
            component.onToggleMessageSelection(selectedMessage.id)
            onDismiss()
        },
        onCopyLink = {
            val link = if (!state.isGroup && !state.isChannel) {
                "tg://openmessage?user_id=${state.chatId}&message_id=${selectedMessage.id shr 20}"
            } else {
                "https://t.me/c/${state.chatId.toString().removePrefix("-100")}/${selectedMessage.id shr 20}"
            }
            clipboardManager.setText(AnnotatedString(link))
            onDismiss()
        },
        onCopy = {
            val textToCopy = when (val content = selectedMessage.content) {
                is MessageContent.Text -> content.text
                is MessageContent.Photo -> content.caption
                is MessageContent.Video -> content.caption
                is MessageContent.Gif -> content.caption
                else -> ""
            }
            if (textToCopy.isNotEmpty()) {
                clipboardManager.setText(AnnotatedString(textToCopy))
            }
            onDismiss()
        },
        onSaveToDownloads = {
            val path = when (val content = selectedMessage.content) {
                is MessageContent.Photo -> content.path
                is MessageContent.Video -> content.path
                is MessageContent.Gif -> content.path
                is MessageContent.Document -> content.path
                is MessageContent.Voice -> content.path
                is MessageContent.VideoNote -> content.path
                else -> null
            }
            path?.let {
                downloadUtils.saveFileToDownloads(it)
            }
            onDismiss()
        },
        onReaction = { reaction ->
            component.onSendReaction(selectedMessage.id, reaction)
            onDismiss()
        },
        onComments = {
            component.onCommentsClick(selectedMessage.id)
            onDismiss()
        },
        onReport = {
            component.onReportMessage(selectedMessage)
            onDismiss()
        },
        onBlock = {
            if (selectedMessage.senderId > 0L) {
                component.onBlockUser(selectedMessage.senderId)
            }
            onDismiss()
        },
        onRestrict = {
            if (selectedMessage.senderId > 0L) {
                component.onRestrictUser(selectedMessage.senderId, ChatPermissionsModel())
            }
            onDismiss()
        },
        onDismiss = onDismiss
    )
}
