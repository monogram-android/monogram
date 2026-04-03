package org.monogram.presentation.features.chats.currentChat.components.channels

import android.content.res.Configuration
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.delay
import org.monogram.domain.models.InlineKeyboardButtonModel
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.currentChat.chatContent.shouldShowDate
import org.monogram.presentation.features.chats.currentChat.components.chats.*

@Composable
fun ChannelMessageBubbleContainer(
    msg: MessageModel,
    olderMsg: MessageModel?,
    newerMsg: MessageModel?,
    autoplayGifs: Boolean = true,
    autoplayVideos: Boolean = true,
    autoDownloadFiles: Boolean = false,
    showLinkPreviews: Boolean = true,
    highlighted: Boolean = false,
    onHighlightConsumed: () -> Unit = {},
    onPhotoClick: (MessageModel) -> Unit,
    onDownloadPhoto: (Int) -> Unit = {},
    onVideoClick: (MessageModel) -> Unit = {},
    onDocumentClick: (MessageModel) -> Unit = {},
    onAudioClick: (MessageModel) -> Unit = {},
    onCancelDownload: (Int) -> Unit = {},
    onReplyClick: (Offset, IntSize, Offset) -> Unit,
    onGoToReply: (MessageModel) -> Unit = {},
    autoDownloadMobile: Boolean = false,
    autoDownloadWifi: Boolean = false,
    autoDownloadRoaming: Boolean = false,
    onReactionClick: (Long, String) -> Unit = { _, _ -> },
    onReplyMarkupButtonClick: (Long, InlineKeyboardButtonModel) -> Unit = { _, _ -> },
    onStickerClick: (Long) -> Unit = {},
    onPollOptionClick: (Long, Int) -> Unit = { _, _ -> },
    onRetractVote: (Long) -> Unit = {},
    onShowVoters: (Long, Int) -> Unit = { _, _ -> },
    onClosePoll: (Long) -> Unit = {},
    onInstantViewClick: ((String) -> Unit)? = null,
    onYouTubeClick: ((String) -> Unit)? = null,
    fontSize: Float,
    letterSpacing: Float,
    bubbleRadius: Float,
    stickerSize: Float = 200f,
    shouldReportPosition: Boolean = false,
    onPositionChange: (Long, Offset, IntSize) -> Unit = { _, _, _ -> },
    onCommentsClick: (Long) -> Unit = {},
    showComments: Boolean = true,
    toProfile: (Long) -> Unit = {},
    onViaBotClick: (String) -> Unit = {},
    downloadUtils: IDownloadUtils,
    isAnyViewerOpen: Boolean = false
) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val maxWidth = if (isLandscape) {
        (screenWidth * 0.7f).coerceAtMost(600.dp)
    } else {
        (screenWidth * 0.94f).coerceAtMost(500.dp)
    }

    val isSameSenderAbove = olderMsg?.senderId == msg.senderId && !shouldShowDate(msg, olderMsg)
    val isSameSenderBelow = newerMsg != null && newerMsg.senderId == msg.senderId && !shouldShowDate(newerMsg, msg)

    val topSpacing = if (!isSameSenderAbove) 12.dp else 2.dp

    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    val animatedColor = remember { Animatable(Color.Transparent) }

    LaunchedEffect(highlighted) {
        if (highlighted) {
            animatedColor.animateTo(highlightColor, animationSpec = tween(300))
            delay(450)
            animatedColor.animateTo(Color.Transparent, animationSpec = tween(1800))
            onHighlightConsumed()
        }
    }

    var outerColumnPosition by remember { mutableStateOf(Offset.Zero) }
    var bubblePosition by remember { mutableStateOf(Offset.Zero) }
    var bubbleSize by remember { mutableStateOf(IntSize.Zero) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(animatedColor.value, RoundedCornerShape(12.dp))
            .onGloballyPositioned { outerColumnPosition = it.positionInWindow() }
            .padding(top = topSpacing)
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
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Bottom
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .widthIn(max = maxWidth)
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        bubblePosition = coordinates.positionInWindow()
                        bubbleSize = coordinates.size
                        if (shouldReportPosition) {
                            onPositionChange(msg.id, bubblePosition, bubbleSize)
                        }
                    }
            ) {
                when (val content = msg.content) {
                    is MessageContent.Text -> {
                        ChannelTextMessageBubble(
                            content = content,
                            msg = msg,
                            isSameSenderAbove = isSameSenderAbove,
                            isSameSenderBelow = isSameSenderBelow,
                            fontSize = fontSize,
                            letterSpacing = letterSpacing,
                            bubbleRadius = bubbleRadius,
                            showLinkPreviews = showLinkPreviews,
                            onReplyClick = onGoToReply,
                            onReactionClick = { onReactionClick(msg.id, it) },
                            onInstantViewClick = onInstantViewClick,
                            onYouTubeClick = onYouTubeClick,
                            onClick = { offset ->
                                onReplyClick(bubblePosition, bubbleSize, bubblePosition + offset)
                            },
                            onLongClick = { offset ->
                                onReplyClick(bubblePosition, bubbleSize, bubblePosition + offset)
                            },
                            onCommentsClick = onCommentsClick,
                            showComments = showComments,
                            toProfile = toProfile,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    is MessageContent.Photo -> {
                        ChannelPhotoMessageBubble(
                            content = content,
                            msg = msg,
                            isSameSenderAbove = isSameSenderAbove,
                            isSameSenderBelow = isSameSenderBelow,
                            fontSize = fontSize,
                            letterSpacing = letterSpacing,
                            bubbleRadius = bubbleRadius,
                            autoDownloadMobile = autoDownloadMobile,
                            autoDownloadWifi = autoDownloadWifi,
                            autoDownloadRoaming = autoDownloadRoaming,
                            onPhotoClick = onPhotoClick,
                            onDownloadPhoto = onDownloadPhoto,
                            onCancelDownload = onCancelDownload,
                            onLongClick = { offset ->
                                onReplyClick(
                                    bubblePosition,
                                    bubbleSize,
                                    bubblePosition + offset
                                )
                            },
                            onReplyClick = onGoToReply,
                            onReactionClick = { onReactionClick(msg.id, it) },
                            onCommentsClick = onCommentsClick,
                            showComments = showComments,
                            toProfile = toProfile,
                            modifier = Modifier.fillMaxWidth(),
                            downloadUtils = downloadUtils
                        )
                    }

                    is MessageContent.Video -> {
                        ChannelVideoMessageBubble(
                            content = content,
                            msg = msg,
                            isSameSenderAbove = isSameSenderAbove,
                            isSameSenderBelow = isSameSenderBelow,
                            fontSize = fontSize,
                            letterSpacing = letterSpacing,
                            bubbleRadius = bubbleRadius,
                            autoDownloadMobile = autoDownloadMobile,
                            autoDownloadWifi = autoDownloadWifi,
                            autoDownloadRoaming = autoDownloadRoaming,
                            autoplayVideos = autoplayVideos,
                            onVideoClick = onVideoClick,
                            onCancelDownload = onCancelDownload,
                            onLongClick = { offset ->
                                onReplyClick(
                                    bubblePosition,
                                    bubbleSize,
                                    bubblePosition + offset
                                )
                            },
                            onReplyClick = onGoToReply,
                            onReactionClick = { onReactionClick(msg.id, it) },
                            onCommentsClick = onCommentsClick,
                            showComments = showComments,
                            toProfile = toProfile,
                            modifier = Modifier.fillMaxWidth(),
                            downloadUtils = downloadUtils,
                            isAnyViewerOpen = isAnyViewerOpen
                        )
                    }

                    is MessageContent.Document -> {
                        DocumentMessageBubble(
                            content = content,
                            msg = msg,
                            isOutgoing = false,
                            isSameSenderAbove = isSameSenderAbove,
                            isSameSenderBelow = isSameSenderBelow,
                            fontSize = fontSize,
                            letterSpacing = letterSpacing,
                            autoDownloadFiles = autoDownloadFiles,
                            autoDownloadMobile = autoDownloadMobile,
                            autoDownloadWifi = autoDownloadWifi,
                            autoDownloadRoaming = autoDownloadRoaming,
                            onDocumentClick = onDocumentClick,
                            onCancelDownload = onCancelDownload,
                            onLongClick = { offset ->
                                onReplyClick(
                                    bubblePosition,
                                    bubbleSize,
                                    bubblePosition + offset
                                )
                            },
                            toProfile = toProfile,
                            modifier = Modifier.fillMaxWidth(),
                            onReplyClick = onGoToReply,
                            onReactionClick = { onReactionClick(msg.id, it) },
                            downloadUtils = downloadUtils
                        )
                    }

                    is MessageContent.Audio -> {
                        AudioMessageBubble(
                            content = content,
                            msg = msg,
                            isOutgoing = false,
                            isSameSenderAbove = isSameSenderAbove,
                            isSameSenderBelow = isSameSenderBelow,
                            fontSize = fontSize,
                            letterSpacing = letterSpacing,
                            autoDownloadFiles = autoDownloadFiles,
                            autoDownloadMobile = autoDownloadMobile,
                            autoDownloadWifi = autoDownloadWifi,
                            autoDownloadRoaming = autoDownloadRoaming,
                            onAudioClick = onAudioClick,
                            onCancelDownload = onCancelDownload,
                            onLongClick = { offset ->
                                onReplyClick(
                                    bubblePosition,
                                    bubbleSize,
                                    bubblePosition + offset
                                )
                            },
                            toProfile = toProfile,
                            modifier = Modifier.fillMaxWidth(),
                            onReplyClick = onGoToReply,
                            onReactionClick = { onReactionClick(msg.id, it) },
                            downloadUtils = downloadUtils
                        )
                    }

                    is MessageContent.Gif -> {
                        ChannelGifMessageBubble(
                            content = content,
                            msg = msg,
                            isSameSenderAbove = isSameSenderAbove,
                            isSameSenderBelow = isSameSenderBelow,
                            fontSize = fontSize,
                            letterSpacing = letterSpacing,
                            bubbleRadius = bubbleRadius,
                            autoDownloadMobile = autoDownloadMobile,
                            autoDownloadWifi = autoDownloadWifi,
                            autoDownloadRoaming = autoDownloadRoaming,
                            autoplayGifs = autoplayGifs,
                            onGifClick = onVideoClick,
                            onCancelDownload = onCancelDownload,
                            onLongClick = { offset ->
                                onReplyClick(
                                    bubblePosition,
                                    bubbleSize,
                                    bubblePosition + offset
                                )
                            },
                            onReplyClick = onGoToReply,
                            onReactionClick = { onReactionClick(msg.id, it) },
                            onCommentsClick = onCommentsClick,
                            showComments = showComments,
                            toProfile = toProfile,
                            modifier = Modifier.fillMaxWidth(),
                            downloadUtils = downloadUtils,
                            isAnyViewerOpen = isAnyViewerOpen
                        )
                    }

                    is MessageContent.Sticker -> {
                        StickerMessageBubble(
                            content = content,
                            msg = msg,
                            isOutgoing = false,
                            stickerSize = stickerSize,
                            onReplyClick = onGoToReply,
                            onReactionClick = { onReactionClick(msg.id, it) },
                            onStickerClick = { onStickerClick(content.setId) },
                            onLongClick = {
                                onReplyClick(
                                    bubblePosition,
                                    bubbleSize,
                                    bubblePosition + (bubbleSize.toSize() / 2f).toOffset()
                                )
                            },
                            toProfile = toProfile
                        )
                    }

                    is MessageContent.Poll -> {
                        ChannelPollMessageBubble(
                            content = content,
                            msg = msg,
                            isSameSenderAbove = isSameSenderAbove,
                            isSameSenderBelow = isSameSenderBelow,
                            fontSize = fontSize,
                            letterSpacing = letterSpacing,
                            bubbleRadius = bubbleRadius,
                            onOptionClick = { onPollOptionClick(msg.id, it) },
                            onRetractVote = { onRetractVote(msg.id) },
                            onShowVoters = { onShowVoters(msg.id, it) },
                            onClosePoll = { onClosePoll(msg.id) },
                            onReplyClick = onGoToReply,
                            onReactionClick = { onReactionClick(msg.id, it) },
                            onLongClick = { offset ->
                                onReplyClick(
                                    bubblePosition,
                                    bubbleSize,
                                    bubblePosition + offset
                                )
                            },
                            onCommentsClick = onCommentsClick,
                            showComments = showComments,
                            toProfile = toProfile
                        )
                    }

                    else -> {}
                }

                msg.replyMarkup?.let { markup ->
                    ReplyMarkupView(
                        replyMarkup = markup,
                        onButtonClick = { onReplyMarkupButtonClick(msg.id, it) }
                    )
                }

                MessageViaBotAttribution(
                    msg = msg,
                    isOutgoing = msg.isOutgoing,
                    onViaBotClick = onViaBotClick,
                    modifier = Modifier.align(Alignment.Start)
                )
            }
        }
    }
}

private fun IntSize.toSize() = androidx.compose.ui.geometry.Size(width.toFloat(), height.toFloat())
private fun androidx.compose.ui.geometry.Size.toOffset() = Offset(width, height)
