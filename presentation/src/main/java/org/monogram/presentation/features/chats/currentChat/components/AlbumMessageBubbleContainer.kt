package org.monogram.presentation.features.chats.currentChat.components

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import org.monogram.domain.models.InlineKeyboardButtonModel
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.currentChat.chatContent.shouldShowDate
import org.monogram.presentation.features.chats.currentChat.components.channels.ChannelAlbumMessageBubble
import org.monogram.presentation.features.chats.currentChat.components.chats.ChatAlbumMessageBubble
import org.monogram.presentation.features.chats.currentChat.components.chats.MessageViaBotAttribution
import org.monogram.presentation.features.chats.currentChat.components.chats.ReplyMarkupView

@Composable
fun AlbumMessageBubbleContainer(
    messages: List<MessageModel>,
    olderMsg: MessageModel? = null,
    newerMsg: MessageModel? = null,
    isGroup: Boolean,
    isChannel: Boolean = false,
    autoplayGifs: Boolean = true,
    autoplayVideos: Boolean = true,
    autoDownloadMobile: Boolean = false,
    autoDownloadWifi: Boolean = false,
    autoDownloadRoaming: Boolean = false,
    onPhotoClick: (MessageModel) -> Unit,
    onDownloadPhoto: (Int) -> Unit = {},
    onVideoClick: (MessageModel) -> Unit = {},
    onDocumentClick: (MessageModel) -> Unit = {},
    onAudioClick: (MessageModel) -> Unit = {},
    onCancelDownload: (Int) -> Unit = {},
    onReplyClick: (Offset, IntSize, Offset) -> Unit,
    onGoToReply: (MessageModel) -> Unit = {},
    onReactionClick: (Long, String) -> Unit = { _, _ -> },
    onReplyMarkupButtonClick: (Long, InlineKeyboardButtonModel) -> Unit = { _, _ -> },
    fontSize: Float = 16f,
    bubbleRadius: Float = 16f,
    shouldReportPosition: Boolean = false,
    onPositionChange: (Long, Offset, IntSize) -> Unit = { _, _, _ -> },
    onCommentsClick: (Long) -> Unit = {},
    showComments: Boolean = true,
    toProfile: (Long) -> Unit,
    onViaBotClick: (String) -> Unit = {},
    canReply: Boolean = false,
    onReplySwipe: (MessageModel) -> Unit = {},
    swipeEnabled: Boolean = true,
    downloadUtils: IDownloadUtils,
    isAnyViewerOpen: Boolean = false
) {
    if (messages.isEmpty()) return

    val orderedMessages = remember(messages) {
        messages
            .distinctBy { it.id }
            .sortedWith(compareBy<MessageModel> { it.date }.thenBy { it.id })
    }

    val firstMsg = orderedMessages.first()
    val lastMsg = orderedMessages.last()
    val isOutgoing = firstMsg.isOutgoing

    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val maxWidth = remember(isChannel, isLandscape, screenWidth) {
        when {
            isChannel -> if (isLandscape) (screenWidth * 0.7f).coerceAtMost(600.dp) else (screenWidth * 0.94f).coerceAtMost(
                500.dp
            )

            isLandscape -> (screenWidth * 0.6f).coerceAtMost(450.dp)
            else -> (screenWidth * 0.85f).coerceAtMost(360.dp)
        }
    }

    val isSameSenderAbove = remember(
        olderMsg?.id,
        olderMsg?.senderId,
        olderMsg?.senderName,
        olderMsg?.senderCustomTitle,
        olderMsg?.date,
        firstMsg.senderId,
        firstMsg.senderName,
        firstMsg.senderCustomTitle,
        firstMsg.date
    ) {
        shouldGroupSenderBlock(
            current = firstMsg,
            neighbor = olderMsg,
            dateBreak = olderMsg?.let { shouldShowDate(firstMsg, it) } ?: true
        )
    }
    val isSameSenderBelow = remember(
        newerMsg?.id,
        newerMsg?.senderId,
        newerMsg?.senderName,
        newerMsg?.senderCustomTitle,
        newerMsg?.date,
        lastMsg.senderId,
        lastMsg.senderName,
        lastMsg.senderCustomTitle,
        lastMsg.date
    ) {
        shouldGroupSenderBlock(
            current = lastMsg,
            neighbor = newerMsg,
            dateBreak = newerMsg?.let { shouldShowDate(it, lastMsg) } ?: true
        )
    }

    val topSpacing = if (isChannel && !isSameSenderAbove) 12.dp else 2.dp

    var outerColumnPosition by remember { mutableStateOf(Offset.Zero) }
    var bubblePosition by remember { mutableStateOf(Offset.Zero) }
    var bubbleSize by remember { mutableStateOf(IntSize.Zero) }

    val dragOffsetX = remember { Animatable(0f) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { outerColumnPosition = it.positionInWindow() }
            .padding(top = topSpacing, bottom = 2.dp)
            .offset { IntOffset(dragOffsetX.value.toInt(), 0) }
            .fastReplyPointer(
                canReply = canReply,
                dragOffsetX = dragOffsetX,
                scope = rememberCoroutineScope(),
                onReplySwipe = { onReplySwipe(lastMsg) },
                maxWidth = maxWidth.value
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        val clickPos = outerColumnPosition + offset
                        val bubbleRect = Rect(bubblePosition, bubbleSize.toSize())
                        if (!bubbleRect.contains(clickPos)) {
                            onReplyClick(bubblePosition, bubbleSize, clickPos)
                        }
                    },
                    onLongPress = { offset ->
                        val clickPos = outerColumnPosition + offset
                        val bubbleRect = Rect(bubblePosition, bubbleSize.toSize())
                        if (!bubbleRect.contains(clickPos)) {
                            onReplyClick(bubblePosition, bubbleSize, clickPos)
                        }
                    }
                )
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isChannel) Arrangement.Center else if (isOutgoing) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom
        ) {
            if (isGroup && !isOutgoing && !isChannel) {
                if (!isSameSenderBelow) {
                    Avatar(
                        path = firstMsg.senderAvatar,
                        fallbackPath = firstMsg.senderPersonalAvatar,
                        name = firstMsg.senderName,
                        size = 40.dp,
                        onClick = { toProfile(firstMsg.senderId) }
                    )
                } else {
                    Spacer(modifier = Modifier.width(40.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            Box(
                modifier = Modifier.wrapContentSize()
            ) {
                Column(
                    modifier = Modifier
                        .then(if (isChannel) Modifier.padding(horizontal = 8.dp) else Modifier)
                        .widthIn(max = maxWidth)
                        .then(if (isChannel) Modifier.fillMaxWidth() else Modifier)
                        .onGloballyPositioned { coordinates ->
                            bubblePosition = coordinates.positionInWindow()
                            bubbleSize = coordinates.size
                            if (shouldReportPosition) {
                                onPositionChange(lastMsg.id, bubblePosition, bubbleSize)
                            }
                        }
                ) {
                    if (isGroup && !isOutgoing && !isChannel && !isSameSenderAbove) {
                        Text(
                            text = firstMsg.senderName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
                        )
                    }

                    if (isChannel) {
                        ChannelAlbumMessageBubble(
                            messages = orderedMessages,
                            isSameSenderAbove = isSameSenderAbove,
                            isSameSenderBelow = isSameSenderBelow,
                            autoplayGifs = autoplayGifs,
                            autoplayVideos = autoplayVideos,
                            autoDownloadMobile = autoDownloadMobile,
                            autoDownloadWifi = autoDownloadWifi,
                            autoDownloadRoaming = autoDownloadRoaming,
                            onPhotoClick = onPhotoClick,
                            onDownloadPhoto = onDownloadPhoto,
                            onVideoClick = onVideoClick,
                            onDocumentClick = onDocumentClick,
                            onAudioClick = onAudioClick,
                            onCancelDownload = onCancelDownload,
                            onLongClick = { offset ->
                                onReplyClick(
                                    bubblePosition,
                                    bubbleSize,
                                    bubblePosition + offset
                                )
                            },
                            onReplyClick = onGoToReply,
                            onReactionClick = { onReactionClick(lastMsg.id, it) },
                            onCommentsClick = onCommentsClick,
                            showComments = showComments,
                            toProfile = toProfile,
                            modifier = Modifier.fillMaxWidth(),
                            fontSize = fontSize,
                            bubbleRadius = bubbleRadius,
                            downloadUtils = downloadUtils,
                            isAnyViewerOpen = isAnyViewerOpen
                        )
                    } else {
                        ChatAlbumMessageBubble(
                            messages = orderedMessages,
                            isOutgoing = isOutgoing,
                            isGroup = isGroup,
                            isSameSenderAbove = isSameSenderAbove,
                            isSameSenderBelow = isSameSenderBelow,
                            autoplayGifs = autoplayGifs,
                            autoplayVideos = autoplayVideos,
                            autoDownloadMobile = autoDownloadMobile,
                            autoDownloadWifi = autoDownloadWifi,
                            autoDownloadRoaming = autoDownloadRoaming,
                            onPhotoClick = onPhotoClick,
                            onDownloadPhoto = onDownloadPhoto,
                            onVideoClick = onVideoClick,
                            onDocumentClick = onDocumentClick,
                            onAudioClick = onAudioClick,
                            onCancelDownload = onCancelDownload,
                            onLongClick = { offset ->
                                onReplyClick(
                                    bubblePosition,
                                    bubbleSize,
                                    bubblePosition + offset
                                )
                            },
                            onReplyClick = onGoToReply,
                            onReactionClick = { onReactionClick(lastMsg.id, it) },
                            toProfile = toProfile,
                            modifier = Modifier,
                            fontSize = fontSize,
                            downloadUtils = downloadUtils,
                            isAnyViewerOpen = isAnyViewerOpen
                        )
                    }

                    lastMsg.replyMarkup?.let { markup ->
                        ReplyMarkupView(
                            replyMarkup = markup,
                            onButtonClick = { onReplyMarkupButtonClick(lastMsg.id, it) }
                        )
                    }

                    MessageViaBotAttribution(
                        msg = lastMsg,
                        isOutgoing = isOutgoing,
                        onViaBotClick = onViaBotClick,
                        modifier = Modifier.align(if (isOutgoing) Alignment.End else Alignment.Start)
                    )
                }

                FastReplyIndicator(
                    modifier = Modifier
                        .align(if (isOutgoing) Alignment.CenterEnd else Alignment.CenterStart),
                    dragOffsetX = dragOffsetX,
                    isOutgoing = isOutgoing,
                    maxWidth = maxWidth,
                )
            }
        }
    }
}
