package org.monogram.presentation.features.chats.currentChat.components

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
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.currentChat.chatContent.shouldShowDate
import org.monogram.presentation.features.chats.currentChat.components.chats.*

@Composable
fun MessageBubbleContainer(
    msg: MessageModel,
    olderMsg: MessageModel?,
    newerMsg: MessageModel?,
    isGroup: Boolean,
    fontSize: Float,
    letterSpacing: Float,
    bubbleRadius: Float = 12f,
    stSize: Float = 200f,
    autoDownloadMobile: Boolean,
    autoDownloadWifi: Boolean,
    autoDownloadRoaming: Boolean,
    autoDownloadFiles: Boolean,
    autoplayGifs: Boolean,
    autoplayVideos: Boolean,
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
    onReactionClick: (Long, String) -> Unit = { _, _ -> },
    onStickerClick: (Long) -> Unit = {},
    onPollOptionClick: (Long, Int) -> Unit = { _, _ -> },
    onRetractVote: (Long) -> Unit = {},
    onShowVoters: (Long, Int) -> Unit = { _, _ -> },
    onClosePoll: (Long) -> Unit = {},
    onInstantViewClick: ((String) -> Unit)? = null,
    onYouTubeClick: ((String) -> Unit)? = null,
    onReplyMarkupButtonClick: (Long, InlineKeyboardButtonModel) -> Unit = { _, _ -> },
    shouldReportPosition: Boolean = false,
    onPositionChange: (Long, Offset, IntSize) -> Unit = { _, _, _ -> },
    toProfile: (Long) -> Unit,
    onViaBotClick: (String) -> Unit = {},
    canReply: Boolean = true,
    onReplySwipe: (MessageModel) -> Unit = {},
    swipeEnabled: Boolean = true,
    downloadUtils: IDownloadUtils,
    isAnyViewerOpen: Boolean = false
) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val maxWidth = remember(isLandscape, screenWidth) {
        if (isLandscape) {
            (screenWidth * 0.6f).coerceAtMost(450.dp)
        } else {
            (screenWidth * 0.85f).coerceAtMost(360.dp)
        }
    }

    val isOutgoing = msg.isOutgoing
    val isSameSenderAbove = remember(olderMsg?.senderId, msg.senderId, olderMsg?.date, msg.date) {
        olderMsg?.senderId == msg.senderId && !shouldShowDate(msg, olderMsg)
    }
    val isSameSenderBelow = remember(newerMsg?.senderId, msg.senderId, newerMsg?.date, msg.date) {
        newerMsg != null && newerMsg.senderId == msg.senderId && !shouldShowDate(newerMsg, msg)
    }

    val topSpacing = if (!isSameSenderAbove) 8.dp else 2.dp

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
            horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom
        ) {
            MessageAvatar(
                msg = msg,
                isGroup = isGroup,
                isOutgoing = isOutgoing,
                isSameSenderBelow = isSameSenderBelow,
                toProfile = toProfile
            )

            Column(
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .widthIn(max = maxWidth)
                    .onGloballyPositioned { coordinates ->
                        bubblePosition = coordinates.positionInWindow()
                        bubbleSize = coordinates.size
                        if (shouldReportPosition) {
                            onPositionChange(msg.id, bubblePosition, bubbleSize)
                        }
                    },
                horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
            ) {
                MessageContentSelector(
                    msg = msg,
                    newerMsg = newerMsg,
                    isOutgoing = isOutgoing,
                    isSameSenderAbove = isSameSenderAbove,
                    isSameSenderBelow = isSameSenderBelow,
                    isGroup = isGroup,
                    fontSize = fontSize,
                    letterSpacing = letterSpacing,
                    bubbleRadius = bubbleRadius,
                    stSize = stSize,
                    autoDownloadMobile = autoDownloadMobile,
                    autoDownloadWifi = autoDownloadWifi,
                    autoDownloadRoaming = autoDownloadRoaming,
                    autoDownloadFiles = autoDownloadFiles,
                    autoplayGifs = autoplayGifs,
                    autoplayVideos = autoplayVideos,
                    showLinkPreviews = showLinkPreviews,
                    onPhotoClick = onPhotoClick,
                    onDownloadPhoto = onDownloadPhoto,
                    onVideoClick = onVideoClick,
                    onDocumentClick = onDocumentClick,
                    onAudioClick = onAudioClick,
                    onCancelDownload = onCancelDownload,
                    onReplyClick = onReplyClick,
                    onGoToReply = onGoToReply,
                    onReactionClick = onReactionClick,
                    onStickerClick = onStickerClick,
                    onPollOptionClick = onPollOptionClick,
                    onRetractVote = onRetractVote,
                    onShowVoters = onShowVoters,
                    onClosePoll = onClosePoll,
                    onInstantViewClick = onInstantViewClick,
                    onYouTubeClick = onYouTubeClick,
                    toProfile = toProfile,
                    bubblePosition = bubblePosition,
                    bubbleSize = bubbleSize,
                    downloadUtils = downloadUtils,
                    isAnyViewerOpen = isAnyViewerOpen
                )

                MessageReplyMarkup(
                    msg = msg,
                    onReplyMarkupButtonClick = onReplyMarkupButtonClick
                )

                MessageViaBotAttribution(
                    msg = msg,
                    isOutgoing = isOutgoing,
                    onViaBotClick = onViaBotClick,
                    modifier = Modifier.align(if (isOutgoing) Alignment.End else Alignment.Start)
                )
            }
        }
    }
}

@Composable
private fun MessageAvatar(
    msg: MessageModel,
    isGroup: Boolean,
    isOutgoing: Boolean,
    isSameSenderBelow: Boolean,
    toProfile: (Long) -> Unit
) {
    if (isGroup && !isOutgoing) {
        if (!isSameSenderBelow) {
            Avatar(
                path = msg.senderAvatar,
                fallbackPath = msg.senderPersonalAvatar,
                name = msg.senderName,
                size = 40.dp,
                isLocal = msg.senderAvatar?.contains("local") ?: false,
                onClick = { toProfile(msg.senderId) })
        } else {
            Spacer(modifier = Modifier.width(40.dp))
        }
        Spacer(modifier = Modifier.width(8.dp))
    }
}

@Composable
private fun MessageContentSelector(
    msg: MessageModel,
    newerMsg: MessageModel?,
    isOutgoing: Boolean,
    isSameSenderAbove: Boolean,
    isSameSenderBelow: Boolean,
    isGroup: Boolean,
    fontSize: Float,
    letterSpacing: Float,
    bubbleRadius: Float,
    stSize: Float,
    autoDownloadMobile: Boolean,
    autoDownloadWifi: Boolean,
    autoDownloadRoaming: Boolean,
    autoDownloadFiles: Boolean,
    autoplayGifs: Boolean,
    autoplayVideos: Boolean,
    showLinkPreviews: Boolean,
    onPhotoClick: (MessageModel) -> Unit,
    onDownloadPhoto: (Int) -> Unit,
    onVideoClick: (MessageModel) -> Unit,
    onDocumentClick: (MessageModel) -> Unit,
    onAudioClick: (MessageModel) -> Unit,
    onCancelDownload: (Int) -> Unit,
    onReplyClick: (Offset, IntSize, Offset) -> Unit,
    onGoToReply: (MessageModel) -> Unit,
    onReactionClick: (Long, String) -> Unit,
    onStickerClick: (Long) -> Unit,
    onPollOptionClick: (Long, Int) -> Unit,
    onRetractVote: (Long) -> Unit,
    onShowVoters: (Long, Int) -> Unit,
    onClosePoll: (Long) -> Unit,
    onInstantViewClick: ((String) -> Unit)?,
    onYouTubeClick: ((String) -> Unit)?,
    toProfile: (Long) -> Unit,
    bubblePosition: Offset,
    bubbleSize: IntSize,
    downloadUtils: IDownloadUtils,
    isAnyViewerOpen: Boolean = false
) {
    Column(
        modifier = Modifier.width(IntrinsicSize.Max),
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        when (val content = msg.content) {
            is MessageContent.Text -> {
                TextMessageBubble(
                    content = content,
                    msg = msg,
                    isOutgoing = isOutgoing,
                    isSameSenderAbove = isSameSenderAbove,
                    isSameSenderBelow = isSameSenderBelow,
                    fontSize = fontSize,
                    letterSpacing = letterSpacing,
                    bubbleRadius = bubbleRadius,
                    isGroup = isGroup,
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
                    toProfile = toProfile
                )
            }

            is MessageContent.Sticker -> {
                StickerMessageBubble(
                    content = content,
                    msg = msg,
                    isOutgoing = isOutgoing,
                    stickerSize = stSize,
                    onReplyClick = onGoToReply,
                    onReactionClick = { onReactionClick(msg.id, it) },
                    onStickerClick = { onStickerClick(it) },
                    onLongClick = {
                        onReplyClick(
                            bubblePosition,
                            bubbleSize,
                            bubblePosition + (bubbleSize.toSize() / 2f).toOffset()
                        )
                    }
                )
            }

            is MessageContent.Photo -> {
                PhotoMessageBubble(
                    content = content,
                    msg = msg,
                    isOutgoing = isOutgoing,
                    isSameSenderAbove = isSameSenderAbove,
                    isSameSenderBelow = isSameSenderBelow,
                    fontSize = fontSize,
                    letterSpacing = letterSpacing,
                    isGroup = isGroup,
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
                    toProfile = toProfile,
                    modifier = Modifier.fillMaxWidth(),
                    downloadUtils = downloadUtils
                )
            }

            is MessageContent.Video -> {
                VideoMessageBubble(
                    content = content,
                    msg = msg,
                    isOutgoing = isOutgoing,
                    isSameSenderAbove = isSameSenderAbove,
                    isSameSenderBelow = isSameSenderBelow,
                    fontSize = fontSize,
                    letterSpacing = letterSpacing,
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
                    toProfile = toProfile,
                    modifier = Modifier.fillMaxWidth(),
                    downloadUtils = downloadUtils,
                    isAnyViewerOpen = isAnyViewerOpen
                )
            }

            is MessageContent.VideoNote -> {
                VideoNoteBubble(
                    content = content,
                    msg = msg,
                    isOutgoing = isOutgoing,
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
                    onReactionClick = { onReactionClick(msg.id, it) }
                )
            }

            is MessageContent.Voice -> {
                VoiceMessageBubble(
                    content = content,
                    msg = msg,
                    isOutgoing = isOutgoing,
                    isSameSenderAbove = isSameSenderAbove,
                    isSameSenderBelow = isSameSenderBelow,
                    fontSize = fontSize,
                    letterSpacing = letterSpacing,
                    isGroup = isGroup,
                    autoDownloadFiles = autoDownloadFiles,
                    autoDownloadMobile = autoDownloadMobile,
                    autoDownloadWifi = autoDownloadWifi,
                    autoDownloadRoaming = autoDownloadRoaming,
                    onVoiceClick = onAudioClick,
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
                    toProfile = toProfile,
                    downloadUtils = downloadUtils
                )
            }

            is MessageContent.Gif -> {
                GifMessageBubble(
                    content = content,
                    msg = msg,
                    isOutgoing = isOutgoing,
                    isSameSenderAbove = isSameSenderAbove,
                    isSameSenderBelow = isSameSenderBelow,
                    fontSize = fontSize,
                    letterSpacing = letterSpacing,
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
                    toProfile = toProfile,
                    downloadUtils = downloadUtils,
                    isAnyViewerOpen = isAnyViewerOpen
                )
            }

            is MessageContent.Document -> {
                DocumentMessageBubble(
                    content = content,
                    msg = msg,
                    isOutgoing = isOutgoing,
                    isSameSenderAbove = isSameSenderAbove,
                    isSameSenderBelow = isSameSenderBelow,
                    fontSize = fontSize,
                    letterSpacing = letterSpacing,
                    isGroup = isGroup,
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
                    onReplyClick = onGoToReply,
                    onReactionClick = { onReactionClick(msg.id, it) },
                    downloadUtils = downloadUtils
                )
            }

            is MessageContent.Audio -> {
                AudioMessageBubble(
                    content = content,
                    msg = msg,
                    isOutgoing = isOutgoing,
                    isSameSenderAbove = isSameSenderAbove,
                    isSameSenderBelow = isSameSenderBelow,
                    fontSize = fontSize,
                    letterSpacing = letterSpacing,
                    isGroup = isGroup,
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
                    onReplyClick = onGoToReply,
                    onReactionClick = { onReactionClick(msg.id, it) },
                    downloadUtils = downloadUtils
                )
            }

            is MessageContent.Contact -> {
                ContactMessageBubble(
                    content = content,
                    msg = msg,
                    isOutgoing = isOutgoing,
                    isSameSenderAbove = isSameSenderAbove,
                    isSameSenderBelow = isSameSenderBelow,
                    fontSize = fontSize,
                    letterSpacing = letterSpacing,
                    bubbleRadius = bubbleRadius,
                    isGroup = isGroup,
                    onClick = { onGoToReply(msg) },
                    onLongClick = {
                        onReplyClick(
                            bubblePosition,
                            bubbleSize,
                            bubblePosition + (bubbleSize.toSize() / 2f).toOffset()
                        )
                    },
                    onReplyClick = onGoToReply,
                    onReactionClick = { onReactionClick(msg.id, it) },
                    toProfile = toProfile,
                    showReactions = msg.reactions.isNotEmpty()
                )
            }

            is MessageContent.Poll -> {
                PollMessageBubble(
                    content = content,
                    msg = msg,
                    isOutgoing = isOutgoing,
                    isSameSenderAbove = isSameSenderAbove,
                    isSameSenderBelow = isSameSenderBelow,
                    fontSize = fontSize,
                    letterSpacing = letterSpacing,
                    bubbleRadius = bubbleRadius,
                    onOptionClick = { onPollOptionClick(msg.id, it) },
                    onRetractVote = { onRetractVote(msg.id) },
                    onLongClick = { offset ->
                        onReplyClick(
                            bubblePosition,
                            bubbleSize,
                            bubblePosition + offset
                        )
                    },
                    onReplyClick = onGoToReply,
                    onReactionClick = { onReactionClick(msg.id, it) },
                    onShowVoters = { onShowVoters(msg.id, it) },
                    onClosePoll = { onClosePoll(msg.id) },
                    toProfile = toProfile
                )
            }

            is MessageContent.Location -> {
                LocationMessageBubble(
                    content = content,
                    msg = msg,
                    isOutgoing = isOutgoing,
                    isSameSenderAbove = isSameSenderAbove,
                    isSameSenderBelow = isSameSenderBelow,
                    fontSize = fontSize,
                    letterSpacing = letterSpacing,
                    isGroup = isGroup,
                    bubbleRadius = bubbleRadius,
                    onClick = { onGoToReply(msg) },
                    onLongClick = {
                        onReplyClick(
                            bubblePosition,
                            bubbleSize,
                            bubblePosition + (bubbleSize.toSize() / 2f).toOffset()
                        )
                    },
                    onReplyClick = onGoToReply,
                    onReactionClick = { onReactionClick(msg.id, it) },
                    toProfile = toProfile
                )
            }

            is MessageContent.Venue -> {
                VenueMessageBubble(
                    content = content,
                    msg = msg,
                    isOutgoing = isOutgoing,
                    isSameSenderAbove = isSameSenderAbove,
                    isSameSenderBelow = isSameSenderBelow,
                    fontSize = fontSize,
                    letterSpacing = letterSpacing,
                    isGroup = isGroup,
                    bubbleRadius = bubbleRadius,
                    onClick = { onGoToReply(msg) },
                    onLongClick = {
                        onReplyClick(
                            bubblePosition,
                            bubbleSize,
                            bubblePosition + (bubbleSize.toSize() / 2f).toOffset()
                        )
                    },
                    onReplyClick = onGoToReply,
                    onReactionClick = { onReactionClick(msg.id, it) },
                    toProfile = toProfile
                )
            }

            else -> {
                // Fallback
            }
        }
    }
}

@Composable
private fun MessageReplyMarkup(
    msg: MessageModel,
    onReplyMarkupButtonClick: (Long, InlineKeyboardButtonModel) -> Unit
) {
    msg.replyMarkup?.let { markup ->
        ReplyMarkupView(
            replyMarkup = markup,
            onButtonClick = { onReplyMarkupButtonClick(msg.id, it) }
        )
    }
}

private fun androidx.compose.ui.geometry.Size.toOffset() = Offset(width, height)
