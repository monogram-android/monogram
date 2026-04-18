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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.monogram.domain.models.InlineKeyboardButtonModel
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.currentChat.chatContent.shouldShowDate
import org.monogram.presentation.features.chats.currentChat.components.channels.*
import org.monogram.presentation.features.chats.currentChat.components.chats.DocumentMessageBubble
import org.monogram.presentation.features.chats.currentChat.components.chats.MessageViaBotAttribution
import org.monogram.presentation.features.chats.currentChat.components.chats.ReplyMarkupView

@Composable
fun ChannelMessageBubbleContainer(
    msg: MessageModel,
    olderMsg: MessageModel?,
    newerMsg: MessageModel?,
    autoplayGifs: Boolean = true,
    autoplayVideos: Boolean = true,
    autoDownloadFiles: Boolean = false,
    highlighted: Boolean = false,
    onHighlightConsumed: () -> Unit = {},
    onPhotoClick: (MessageModel) -> Unit,
    onDownloadPhoto: (Int) -> Unit = {},
    onVideoClick: (MessageModel) -> Unit = {},
    onDocumentClick: (MessageModel) -> Unit = {},
    onAudioClick: (MessageModel) -> Unit = {},
    onReplyClick: (Offset, IntSize, Offset) -> Unit,
    onGoToReply: (MessageModel) -> Unit = {},
    autoDownloadMobile: Boolean = false,
    autoDownloadWifi: Boolean = false,
    autoDownloadRoaming: Boolean = false,
    onReactionClick: (Long, String) -> Unit = { _, _ -> },
    onReplyMarkupButtonClick: (Long, InlineKeyboardButtonModel) -> Unit = { _, _ -> },
    onYouTubeClick: ((String) -> Unit)? = null,
    onInstantViewClick: ((String) -> Unit)? = null,
    fontSize: Float,
    letterSpacing: Float,
    bubbleRadius: Float,
    stickerSize: Float = 200f,
    onCommentsClick: (Long) -> Unit = {},
    showComments: Boolean = true,
    toProfile: (Long) -> Unit = {},
    onViaBotClick: (String) -> Unit = {},
    downloadUtils: IDownloadUtils
) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val maxWidth = if (isLandscape) {
        (screenWidth * 0.6f).coerceAtMost(450.dp)
    } else {
        (screenWidth * 0.92f).coerceAtMost(480.dp)
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(animatedColor.value, RoundedCornerShape(12.dp))
            .padding(top = topSpacing)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Bottom
        ) {
            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = maxWidth)
            ) {
                var bubblePosition by remember { mutableStateOf(Offset.Zero) }
                var bubbleSize by remember { mutableStateOf(IntSize.Zero) }
                val bubbleModifier = Modifier
                    .onGloballyPositioned { coordinates ->
                        bubblePosition = coordinates.positionInWindow()
                        bubbleSize = coordinates.size
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { offset ->
                                onReplyClick(bubblePosition, bubbleSize, bubblePosition + offset)
                            },
                            onLongPress = { offset ->
                                onReplyClick(bubblePosition, bubbleSize, bubblePosition + offset)
                            }
                        )
                    }

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
                            onReplyClick = onGoToReply,
                            onReactionClick = { onReactionClick(msg.id, it) },
                            onYouTubeClick = onYouTubeClick,
                            onInstantViewClick = onInstantViewClick,
                            toProfile = toProfile,
                            modifier = bubbleModifier.fillMaxWidth()
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
                            autoDownloadMobile = autoDownloadMobile,
                            autoDownloadWifi = autoDownloadWifi,
                            autoDownloadRoaming = autoDownloadRoaming,
                            onPhotoClick = onPhotoClick,
                            onDownloadPhoto = onDownloadPhoto,
                            onLongClick = { offset -> onReplyClick(bubblePosition, bubbleSize, offset) },
                            onReplyClick = onGoToReply,
                            onReactionClick = { onReactionClick(msg.id, it) },
                            modifier = bubbleModifier.fillMaxWidth(),
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
                            autoDownloadMobile = autoDownloadMobile,
                            autoDownloadWifi = autoDownloadWifi,
                            autoDownloadRoaming = autoDownloadRoaming,
                            autoplayVideos = autoplayVideos,
                            onVideoClick = onVideoClick,
                            onLongClick = { offset -> onReplyClick(bubblePosition, bubbleSize, offset) },
                            onReplyClick = onGoToReply,
                            onReactionClick = { onReactionClick(msg.id, it) },
                            modifier = bubbleModifier.fillMaxWidth(),
                            downloadUtils = downloadUtils
                        )
                    }

                    is MessageContent.Voice -> {
                        ChannelVoiceMessageBubble(
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
                            autoDownloadFiles = autoDownloadFiles,
                            onVoiceClick = onAudioClick,
                            onLongClick = { offset -> onReplyClick(bubblePosition, bubbleSize, offset) },
                            onReplyClick = onGoToReply,
                            onReactionClick = { onReactionClick(msg.id, it) },
                            onCommentsClick = onCommentsClick,
                            showComments = showComments,
                            toProfile = toProfile,
                            modifier = bubbleModifier.fillMaxWidth(),
                            downloadUtils = downloadUtils
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
                            onLongClick = { offset ->
                                onReplyClick(
                                    bubblePosition,
                                    bubbleSize,
                                    bubblePosition + offset
                                )
                            },
                            modifier = bubbleModifier.fillMaxWidth(),
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
                            autoDownloadMobile = autoDownloadMobile,
                            autoDownloadWifi = autoDownloadWifi,
                            autoDownloadRoaming = autoDownloadRoaming,
                            autoplayGifs = autoplayGifs,
                            onGifClick = onVideoClick,
                            onLongClick = { offset -> onReplyClick(bubblePosition, bubbleSize, offset) },
                            onReplyClick = onGoToReply,
                            onReactionClick = { onReactionClick(msg.id, it) },
                            modifier = bubbleModifier.fillMaxWidth(),
                            downloadUtils = downloadUtils
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
            Spacer(modifier = Modifier.width(12.dp))
        }
    }
}
