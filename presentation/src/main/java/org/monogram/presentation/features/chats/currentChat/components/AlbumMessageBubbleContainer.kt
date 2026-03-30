package org.monogram.presentation.features.chats.currentChat.components

import android.content.res.Configuration
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
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
    videoPlayerPool: VideoPlayerPool,
    isAnyViewerOpen: Boolean = false
) {
    if (messages.isEmpty()) return

    val firstMsg = messages.first()
    val lastMsg = messages.last()
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

    val isSameSenderAbove = remember(olderMsg?.senderId, firstMsg.senderId, olderMsg?.date, firstMsg.date) {
        olderMsg?.senderId == firstMsg.senderId && !shouldShowDate(firstMsg, olderMsg)
    }
    val isSameSenderBelow = remember(newerMsg?.senderId, lastMsg.senderId, newerMsg?.date, lastMsg.date) {
        newerMsg != null && newerMsg.senderId == lastMsg.senderId && !shouldShowDate(newerMsg, lastMsg)
    }

    val topSpacing = if (isChannel && !isSameSenderAbove) 12.dp else 2.dp

    var outerColumnPosition by remember { mutableStateOf(Offset.Zero) }
    var bubblePosition by remember { mutableStateOf(Offset.Zero) }
    var bubbleSize by remember { mutableStateOf(IntSize.Zero) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { outerColumnPosition = it.positionInWindow() }
            .padding(top = topSpacing, bottom = 2.dp)
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
                Avatar(
                    path = firstMsg.senderAvatar,
                    fallbackPath = firstMsg.senderPersonalAvatar,
                    name = firstMsg.senderName,
                    size = 40.dp,
                    onClick = { toProfile(firstMsg.senderId) },
                    videoPlayerPool = videoPlayerPool
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

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
                if (isGroup && !isOutgoing && !isChannel) {
                    Text(
                        text = firstMsg.senderName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
                    )
                }

                if (isChannel) {
                    ChannelAlbumMessageBubble(
                        messages = messages,
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
                        videoPlayerPool = videoPlayerPool,
                        isAnyViewerOpen = isAnyViewerOpen
                    )
                } else {
                    ChatAlbumMessageBubble(
                        messages = messages,
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
                        videoPlayerPool = videoPlayerPool,
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
        }
    }
}
