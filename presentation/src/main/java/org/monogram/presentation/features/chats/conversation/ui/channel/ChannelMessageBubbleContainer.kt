package org.monogram.presentation.features.chats.conversation.ui.channel

import android.content.res.Configuration
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.delay
import org.monogram.domain.models.ForwardInfo
import org.monogram.domain.models.InlineKeyboardButtonModel
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.conversation.ui.FastReplyIndicator
import org.monogram.presentation.features.chats.conversation.ui.MessageAppearanceConfig
import org.monogram.presentation.features.chats.conversation.ui.MessageBubbleLayoutTracker
import org.monogram.presentation.features.chats.conversation.ui.MessageRowBehaviorConfig
import org.monogram.presentation.features.chats.conversation.ui.MessageRowUiFlags
import org.monogram.presentation.features.chats.conversation.ui.MessageSenderGrouping
import org.monogram.presentation.features.chats.conversation.ui.fastReplyPointer
import org.monogram.presentation.features.chats.conversation.ui.message.AudioMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.DocumentMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.MessageViaBotAttribution
import org.monogram.presentation.features.chats.conversation.ui.message.ReplyMarkupView
import org.monogram.presentation.features.chats.conversation.ui.message.StickerMessageBubble

@Composable
internal fun ChannelMessageBubbleContainer(
    msg: MessageModel,
    newerMsg: MessageModel?,
    appearance: MessageAppearanceConfig,
    behavior: MessageRowBehaviorConfig,
    uiFlags: MessageRowUiFlags = MessageRowUiFlags(),
    senderGrouping: MessageSenderGrouping,
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
    onReplyMarkupButtonClick: (Long, InlineKeyboardButtonModel) -> Unit = { _, _ -> },
    onStickerClick: (Long) -> Unit = {},
    onPollOptionClick: (Long, Int) -> Unit = { _, _ -> },
    onRetractVote: (Long) -> Unit = {},
    onShowVoters: (Long, Int) -> Unit = { _, _ -> },
    onClosePoll: (Long) -> Unit = {},
    onInstantViewClick: ((String) -> Unit)? = null,
    onYouTubeClick: ((String) -> Unit)? = null,
    onPositionChange: (Long, Offset, IntSize) -> Unit = { _, _, _ -> },
    onCommentsClick: (Long) -> Unit = {},
    showComments: Boolean = true,
    toProfile: (Long) -> Unit = {},
    onForwardOriginClick: (ForwardInfo) -> Unit = {},
    onViaBotClick: (String) -> Unit = {},
    onReplySwipe: (MessageModel) -> Unit = {},
    downloadUtils: IDownloadUtils,
) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val maxWidth = if (isLandscape) {
        (screenWidth * 0.7f).coerceAtMost(600.dp)
    } else {
        (screenWidth * 0.94f).coerceAtMost(500.dp)
    }

    val topSpacing = if (!senderGrouping.isSameSenderAbove) 12.dp else 2.dp

    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    val animatedColor = remember { Animatable(Color.Transparent) }

    LaunchedEffect(uiFlags.isHighlighted) {
        if (uiFlags.isHighlighted) {
            animatedColor.animateTo(highlightColor, animationSpec = tween(300))
            delay(450)
            animatedColor.animateTo(Color.Transparent, animationSpec = tween(1800))
            onHighlightConsumed()
        }
    }

    val dragOffsetX = remember { androidx.compose.animation.core.Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    val layoutTracker = remember { MessageBubbleLayoutTracker() }
    val onReplyClickState by rememberUpdatedState(onReplyClick)
    val onPositionChangeState by rememberUpdatedState(onPositionChange)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(animatedColor.value, RoundedCornerShape(12.dp))
            .onGloballyPositioned { layoutTracker.outerColumnPosition = it.positionInWindow() }
            .padding(top = topSpacing)
            .offset { IntOffset(dragOffsetX.value.toInt(), 0) }
            .fastReplyPointer(
                canReply = behavior.canReply && behavior.swipeEnabled,
                dragOffsetX = dragOffsetX,
                scope = coroutineScope,
                onReplySwipe = { onReplySwipe(msg) },
                maxWidth = maxWidth.value
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        val clickPos = layoutTracker.outerColumnPosition + offset
                        val bubbleRect =
                            Rect(layoutTracker.bubblePosition, layoutTracker.bubbleSize.toSize())
                        if (!bubbleRect.contains(clickPos)) {
                            onReplyClickState(
                                layoutTracker.bubblePosition,
                                layoutTracker.bubbleSize,
                                clickPos
                            )
                        }
                    },
                    onLongPress = { offset ->
                        val clickPos = layoutTracker.outerColumnPosition + offset
                        val bubbleRect =
                            Rect(layoutTracker.bubblePosition, layoutTracker.bubbleSize.toSize())
                        if (!bubbleRect.contains(clickPos)) {
                            onReplyClickState(
                                layoutTracker.bubblePosition,
                                layoutTracker.bubbleSize,
                                clickPos
                            )
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
            Box(
                modifier = Modifier.wrapContentSize()
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .widthIn(max = maxWidth)
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            layoutTracker.bubblePosition = coordinates.positionInWindow()
                            layoutTracker.bubbleSize = coordinates.size
                            if (uiFlags.shouldReportPosition) {
                                onPositionChangeState(
                                    msg.id,
                                    layoutTracker.bubblePosition,
                                    layoutTracker.bubbleSize
                                )
                            }
                        }
                ) {
                    when (val content = msg.content) {
                        is MessageContent.Text -> {
                            ChannelTextMessageBubble(
                                content = content,
                                msg = msg,
                                isSameSenderAbove = senderGrouping.isSameSenderAbove,
                                isSameSenderBelow = senderGrouping.isSameSenderBelow,
                                fontSize = appearance.fontSize,
                                letterSpacing = appearance.letterSpacing,
                                bubbleRadius = appearance.bubbleRadius,
                                showLinkPreviews = appearance.showLinkPreviews,
                                onReplyClick = onGoToReply,
                                onReactionClick = { onReactionClick(msg.id, it) },
                                onInstantViewClick = onInstantViewClick,
                                onYouTubeClick = onYouTubeClick,
                                onClick = { offset ->
                                    onReplyClickState(
                                        layoutTracker.bubblePosition,
                                        layoutTracker.bubbleSize,
                                        layoutTracker.bubblePosition + offset
                                    )
                                },
                                onLongClick = { offset ->
                                    onReplyClickState(
                                        layoutTracker.bubblePosition,
                                        layoutTracker.bubbleSize,
                                        layoutTracker.bubblePosition + offset
                                    )
                                },
                                onCommentsClick = onCommentsClick,
                                showComments = showComments,
                                toProfile = toProfile,
                                onForwardOriginClick = onForwardOriginClick,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        is MessageContent.Photo -> {
                            ChannelPhotoMessageBubble(
                                content = content,
                                msg = msg,
                                isSameSenderAbove = senderGrouping.isSameSenderAbove,
                                isSameSenderBelow = senderGrouping.isSameSenderBelow,
                                fontSize = appearance.fontSize,
                                letterSpacing = appearance.letterSpacing,
                                bubbleRadius = appearance.bubbleRadius,
                                autoDownloadMobile = appearance.autoDownloadMobile,
                                autoDownloadWifi = appearance.autoDownloadWifi,
                                autoDownloadRoaming = appearance.autoDownloadRoaming,
                                onPhotoClick = onPhotoClick,
                                onDownloadPhoto = onDownloadPhoto,
                                onCancelDownload = onCancelDownload,
                                onLongClick = { offset ->
                                    onReplyClickState(
                                        layoutTracker.bubblePosition,
                                        layoutTracker.bubbleSize,
                                        layoutTracker.bubblePosition + offset
                                    )
                                },
                                onReplyClick = onGoToReply,
                                onReactionClick = { onReactionClick(msg.id, it) },
                                onCommentsClick = onCommentsClick,
                                showComments = showComments,
                                toProfile = toProfile,
                                onForwardOriginClick = onForwardOriginClick,
                                modifier = Modifier.fillMaxWidth(),
                                downloadUtils = downloadUtils
                            )
                        }

                        is MessageContent.Video -> {
                            ChannelVideoMessageBubble(
                                content = content,
                                msg = msg,
                                isSameSenderAbove = senderGrouping.isSameSenderAbove,
                                isSameSenderBelow = senderGrouping.isSameSenderBelow,
                                fontSize = appearance.fontSize,
                                letterSpacing = appearance.letterSpacing,
                                bubbleRadius = appearance.bubbleRadius,
                                autoDownloadMobile = appearance.autoDownloadMobile,
                                autoDownloadWifi = appearance.autoDownloadWifi,
                                autoDownloadRoaming = appearance.autoDownloadRoaming,
                                autoplayVideos = appearance.autoplayVideos,
                                onVideoClick = onVideoClick,
                                onCancelDownload = onCancelDownload,
                                onLongClick = { offset ->
                                    onReplyClickState(
                                        layoutTracker.bubblePosition,
                                        layoutTracker.bubbleSize,
                                        layoutTracker.bubblePosition + offset
                                    )
                                },
                                onReplyClick = onGoToReply,
                                onReactionClick = { onReactionClick(msg.id, it) },
                                onCommentsClick = onCommentsClick,
                                showComments = showComments,
                                toProfile = toProfile,
                                onForwardOriginClick = onForwardOriginClick,
                                modifier = Modifier.fillMaxWidth(),
                                downloadUtils = downloadUtils,
                                isAnyViewerOpen = behavior.isAnyViewerOpen
                            )
                        }

                        is MessageContent.Document -> {
                            DocumentMessageBubble(
                                content = content,
                                msg = msg,
                                isOutgoing = false,
                                isSameSenderAbove = senderGrouping.isSameSenderAbove,
                                isSameSenderBelow = senderGrouping.isSameSenderBelow,
                                fontSize = appearance.fontSize,
                                letterSpacing = appearance.letterSpacing,
                                autoDownloadFiles = appearance.autoDownloadFiles,
                                autoDownloadMobile = appearance.autoDownloadMobile,
                                autoDownloadWifi = appearance.autoDownloadWifi,
                                autoDownloadRoaming = appearance.autoDownloadRoaming,
                                onDocumentClick = onDocumentClick,
                                onCancelDownload = onCancelDownload,
                                onLongClick = { offset ->
                                    onReplyClickState(
                                        layoutTracker.bubblePosition,
                                        layoutTracker.bubbleSize,
                                        layoutTracker.bubblePosition + offset
                                    )
                                },
                                toProfile = toProfile,
                                onForwardOriginClick = onForwardOriginClick,
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
                                isSameSenderAbove = senderGrouping.isSameSenderAbove,
                                isSameSenderBelow = senderGrouping.isSameSenderBelow,
                                fontSize = appearance.fontSize,
                                letterSpacing = appearance.letterSpacing,
                                autoDownloadFiles = appearance.autoDownloadFiles,
                                autoDownloadMobile = appearance.autoDownloadMobile,
                                autoDownloadWifi = appearance.autoDownloadWifi,
                                autoDownloadRoaming = appearance.autoDownloadRoaming,
                                onAudioClick = onAudioClick,
                                onCancelDownload = onCancelDownload,
                                onLongClick = { offset ->
                                    onReplyClickState(
                                        layoutTracker.bubblePosition,
                                        layoutTracker.bubbleSize,
                                        layoutTracker.bubblePosition + offset
                                    )
                                },
                                toProfile = toProfile,
                                onForwardOriginClick = onForwardOriginClick,
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
                                isSameSenderAbove = senderGrouping.isSameSenderAbove,
                                isSameSenderBelow = senderGrouping.isSameSenderBelow,
                                fontSize = appearance.fontSize,
                                letterSpacing = appearance.letterSpacing,
                                bubbleRadius = appearance.bubbleRadius,
                                autoDownloadMobile = appearance.autoDownloadMobile,
                                autoDownloadWifi = appearance.autoDownloadWifi,
                                autoDownloadRoaming = appearance.autoDownloadRoaming,
                                autoplayGifs = appearance.autoplayGifs,
                                onGifClick = onVideoClick,
                                onCancelDownload = onCancelDownload,
                                onLongClick = { offset ->
                                    onReplyClickState(
                                        layoutTracker.bubblePosition,
                                        layoutTracker.bubbleSize,
                                        layoutTracker.bubblePosition + offset
                                    )
                                },
                                onReplyClick = onGoToReply,
                                onReactionClick = { onReactionClick(msg.id, it) },
                                onCommentsClick = onCommentsClick,
                                showComments = showComments,
                                toProfile = toProfile,
                                onForwardOriginClick = onForwardOriginClick,
                                modifier = Modifier.fillMaxWidth(),
                                downloadUtils = downloadUtils,
                                isAnyViewerOpen = behavior.isAnyViewerOpen
                            )
                        }

                        is MessageContent.Sticker -> {
                            StickerMessageBubble(
                                content = content,
                                msg = msg,
                                isOutgoing = false,
                                stickerSize = appearance.stickerSize,
                                onReplyClick = onGoToReply,
                                onReactionClick = { onReactionClick(msg.id, it) },
                                onStickerClick = { onStickerClick(content.setId) },
                                onLongClick = {
                                    onReplyClickState(
                                        layoutTracker.bubblePosition,
                                        layoutTracker.bubbleSize,
                                        layoutTracker.bubblePosition + (layoutTracker.bubbleSize.toSize() / 2f).toOffset()
                                    )
                                },
                                toProfile = toProfile,
                                onForwardOriginClick = onForwardOriginClick
                            )
                        }

                        is MessageContent.Poll -> {
                            ChannelPollMessageBubble(
                                content = content,
                                msg = msg,
                                isSameSenderAbove = senderGrouping.isSameSenderAbove,
                                isSameSenderBelow = senderGrouping.isSameSenderBelow,
                                fontSize = appearance.fontSize,
                                letterSpacing = appearance.letterSpacing,
                                bubbleRadius = appearance.bubbleRadius,
                                onOptionClick = { onPollOptionClick(msg.id, it) },
                                onRetractVote = { onRetractVote(msg.id) },
                                onShowVoters = { onShowVoters(msg.id, it) },
                                onClosePoll = { onClosePoll(msg.id) },
                                onReplyClick = onGoToReply,
                                onReactionClick = { onReactionClick(msg.id, it) },
                                onLongClick = { offset ->
                                    onReplyClickState(
                                        layoutTracker.bubblePosition,
                                        layoutTracker.bubbleSize,
                                        layoutTracker.bubblePosition + offset
                                    )
                                },
                                onCommentsClick = onCommentsClick,
                                showComments = showComments,
                                toProfile = toProfile,
                                onForwardOriginClick = onForwardOriginClick
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

                FastReplyIndicator(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    dragOffsetX = dragOffsetX,
                    inverseOffset = isLandscape,
                    maxWidth = maxWidth,
                )
            }
        }
    }
}

private fun IntSize.toSize() = androidx.compose.ui.geometry.Size(width.toFloat(), height.toFloat())
private fun androidx.compose.ui.geometry.Size.toOffset() = Offset(width, height)

