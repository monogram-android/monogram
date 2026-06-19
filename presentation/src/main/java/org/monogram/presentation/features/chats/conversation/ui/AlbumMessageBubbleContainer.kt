package org.monogram.presentation.features.chats.conversation.ui

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import org.monogram.domain.models.ForwardInfo
import org.monogram.domain.models.InlineKeyboardButtonModel
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.conversation.ui.channel.ChannelAlbumMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.ChatAlbumMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.MessageViaBotAttribution
import org.monogram.presentation.features.chats.conversation.ui.message.ReplyMarkupView

@Composable
internal fun AlbumMessageBubbleContainer(
    messages: List<MessageModel>,
    appearance: MessageAppearanceConfig,
    behavior: MessageRowBehaviorConfig,
    uiFlags: MessageRowUiFlags = MessageRowUiFlags(),
    senderGrouping: MessageSenderGrouping,
    onPhotoClick: (MessageModel) -> Unit,
    onDownloadPhoto: (Int) -> Unit = {},
    onVideoClick: (MessageModel) -> Unit = {},
    onDownloadVideo: (Int) -> Unit = {},
    onDocumentClick: (MessageModel) -> Unit = {},
    onAudioClick: (MessageModel) -> Unit = {},
    onCancelDownload: (Int) -> Unit = {},
    onReplyClick: (Offset, IntSize, Offset) -> Unit,
    onMessageLongPress: ((MessageModel, Offset, IntSize, Offset) -> Unit)? = null,
    onGoToReply: (MessageModel) -> Unit = {},
    onReactionClick: (Long, String) -> Unit = { _, _ -> },
    onReplyMarkupButtonClick: (Long, InlineKeyboardButtonModel) -> Unit = { _, _ -> },
    onPositionChange: (Long, Offset, IntSize) -> Unit = { _, _, _ -> },
    onCommentsClick: (Long) -> Unit = {},
    showComments: Boolean = true,
    toProfile: (Long) -> Unit,
    onForwardOriginClick: (ForwardInfo) -> Unit = {},
    onViaBotClick: (String) -> Unit = {},
    onReplySwipe: (MessageModel) -> Unit = {},
    downloadUtils: IDownloadUtils
) {
    if (messages.isEmpty()) return

    val orderedMessages = remember(messages, appearance.showReactions) {
        val sorted = messages
            .distinctBy { it.id }
            .sortedWith(compareBy<MessageModel> { it.date }.thenBy { it.id })
        if (appearance.showReactions) sorted else sorted.map { it.copy(reactions = emptyList()) }
    }

    val firstMsg = orderedMessages.first()
    val lastMsg = orderedMessages.last()
    val isOutgoing = firstMsg.isOutgoing

    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val maxWidth = remember(behavior.isChannel, isLandscape, screenWidth) {
        when {
            behavior.isChannel -> if (isLandscape) (screenWidth * 0.7f).coerceAtMost(600.dp) else (screenWidth * 0.94f).coerceAtMost(
                500.dp
            )

            isLandscape -> (screenWidth * 0.6f).coerceAtMost(450.dp)
            else -> (screenWidth * 0.85f).coerceAtMost(360.dp)
        }
    }

    val topSpacing = if (behavior.isChannel && !senderGrouping.isSameSenderAbove) 12.dp else 2.dp
    val dragOffsetX = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    val layoutTracker = remember { MessageBubbleLayoutTracker() }
    val onReplyClickState by rememberUpdatedState(onReplyClick)
    val onMessageLongPressState by rememberUpdatedState(onMessageLongPress)
    val onPositionChangeState by rememberUpdatedState(onPositionChange)
    val selectionBadgeAlignment = if (behavior.isChannel || isOutgoing) {
        MessageSelectionBadgeAlignment.TopEnd
    } else {
        MessageSelectionBadgeAlignment.TopStart
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { layoutTracker.outerColumnPosition = it.positionInWindow() }
            .padding(top = topSpacing, bottom = 2.dp)
            .offset { IntOffset(dragOffsetX.value.toInt(), 0) }
            .fastReplyPointer(
                canReply = behavior.canReply && behavior.swipeEnabled,
                dragOffsetX = dragOffsetX,
                scope = coroutineScope,
                onReplySwipe = { onReplySwipe(lastMsg) },
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
                            if (onMessageLongPressState != null) {
                                onMessageLongPressState?.invoke(
                                    lastMsg,
                                    layoutTracker.bubblePosition,
                                    layoutTracker.bubbleSize,
                                    clickPos
                                )
                            } else {
                                onReplyClickState(
                                    layoutTracker.bubblePosition,
                                    layoutTracker.bubbleSize,
                                    clickPos
                                )
                            }
                        }
                    }
                )
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (behavior.isChannel) Arrangement.Center else if (isOutgoing) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom
        ) {
            if (behavior.isGroup && !isOutgoing && !behavior.isChannel) {
                if (!senderGrouping.isSameSenderBelow) {
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
                        .then(if (behavior.isChannel) Modifier.padding(horizontal = 8.dp) else Modifier)
                        .widthIn(max = maxWidth)
                        .then(if (behavior.isChannel) Modifier.fillMaxWidth() else Modifier)
                ) {
                    if (behavior.isGroup && !isOutgoing && !behavior.isChannel && !senderGrouping.isSameSenderAbove) {
                        Text(
                            text = firstMsg.senderName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
                        )
                    }

                    MessageSelectionDecoration(
                        isSelectionMode = behavior.isSelectionMode,
                        isSelected = uiFlags.isSelected,
                        badgeAlignment = selectionBadgeAlignment,
                        modifier = Modifier.onGloballyPositioned { coordinates ->
                            layoutTracker.bubblePosition = coordinates.positionInWindow()
                            layoutTracker.bubbleSize = coordinates.size
                            if (uiFlags.shouldReportPosition) {
                                onPositionChangeState(
                                    lastMsg.id,
                                    layoutTracker.bubblePosition,
                                    layoutTracker.bubbleSize
                                )
                            }
                        }
                    ) {
                        Column {
                            if (behavior.isChannel) {
                                ChannelAlbumMessageBubble(
                                    messages = orderedMessages,
                                    isSameSenderAbove = senderGrouping.isSameSenderAbove,
                                    isSameSenderBelow = senderGrouping.isSameSenderBelow,
                                    autoplayGifs = appearance.autoplayGifs,
                                    autoplayVideos = appearance.autoplayVideos,
                                    autoDownloadMobile = appearance.autoDownloadMobile,
                                    autoDownloadWifi = appearance.autoDownloadWifi,
                                    autoDownloadRoaming = appearance.autoDownloadRoaming,
                                    onPhotoClick = onPhotoClick,
                                    onDownloadPhoto = onDownloadPhoto,
                                    onVideoClick = onVideoClick,
                                    onDownloadVideo = onDownloadVideo,
                                    onDocumentClick = onDocumentClick,
                                    onAudioClick = onAudioClick,
                                    onCancelDownload = onCancelDownload,
                                    onLongClick = { offset ->
                                        onReplyClickState(
                                            layoutTracker.bubblePosition,
                                            layoutTracker.bubbleSize,
                                            offset
                                        )
                                    },
                                    onMessageLongPress = { tappedMessage, offset ->
                                        if (onMessageLongPressState != null) {
                                            onMessageLongPressState?.invoke(
                                                tappedMessage,
                                                layoutTracker.bubblePosition,
                                                layoutTracker.bubbleSize,
                                                offset
                                            )
                                        } else {
                                            onReplyClickState(
                                                layoutTracker.bubblePosition,
                                                layoutTracker.bubbleSize,
                                                offset
                                            )
                                        }
                                    },
                                    onReplyClick = onGoToReply,
                                    onReactionClick = { onReactionClick(lastMsg.id, it) },
                                    onCommentsClick = onCommentsClick,
                                    showComments = showComments,
                                    toProfile = toProfile,
                                    onForwardOriginClick = onForwardOriginClick,
                                    modifier = Modifier.fillMaxWidth(),
                                    fontSize = appearance.fontSize,
                                    bubbleRadius = appearance.bubbleRadius,
                                    downloadUtils = downloadUtils,
                                    isAnyViewerOpen = behavior.isAnyViewerOpen
                                )
                            } else {
                                ChatAlbumMessageBubble(
                                    messages = orderedMessages,
                                    isOutgoing = isOutgoing,
                                    isGroup = behavior.isGroup,
                                    isSameSenderAbove = senderGrouping.isSameSenderAbove,
                                    isSameSenderBelow = senderGrouping.isSameSenderBelow,
                                    autoplayGifs = appearance.autoplayGifs,
                                    autoplayVideos = appearance.autoplayVideos,
                                    autoDownloadMobile = appearance.autoDownloadMobile,
                                    autoDownloadWifi = appearance.autoDownloadWifi,
                                    autoDownloadRoaming = appearance.autoDownloadRoaming,
                                    onPhotoClick = onPhotoClick,
                                    onDownloadPhoto = onDownloadPhoto,
                                    onVideoClick = onVideoClick,
                                    onDownloadVideo = onDownloadVideo,
                                    onDocumentClick = onDocumentClick,
                                    onAudioClick = onAudioClick,
                                    onCancelDownload = onCancelDownload,
                                    onLongClick = { offset ->
                                        onReplyClickState(
                                            layoutTracker.bubblePosition,
                                            layoutTracker.bubbleSize,
                                            offset
                                        )
                                    },
                                    onMessageLongPress = { tappedMessage, offset ->
                                        if (onMessageLongPressState != null) {
                                            onMessageLongPressState?.invoke(
                                                tappedMessage,
                                                layoutTracker.bubblePosition,
                                                layoutTracker.bubbleSize,
                                                offset
                                            )
                                        } else {
                                            onReplyClickState(
                                                layoutTracker.bubblePosition,
                                                layoutTracker.bubbleSize,
                                                offset
                                            )
                                        }
                                    },
                                    onReplyClick = onGoToReply,
                                    onReactionClick = { onReactionClick(lastMsg.id, it) },
                                    toProfile = toProfile,
                                    onForwardOriginClick = onForwardOriginClick,
                                    modifier = Modifier,
                                    fontSize = appearance.fontSize,
                                    downloadUtils = downloadUtils,
                                    isAnyViewerOpen = behavior.isAnyViewerOpen
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

                FastReplyIndicator(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    dragOffsetX = dragOffsetX,
                    isOutgoing = isOutgoing,
                    maxWidth = maxWidth,
                )
            }
        }
    }
}

