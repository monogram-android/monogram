package org.monogram.presentation.features.chats.conversation.ui

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
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
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.conversation.ui.message.AudioMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.ContactMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.DocumentMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.GifMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.LocationMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.MessageViaBotAttribution
import org.monogram.presentation.features.chats.conversation.ui.message.PhotoMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.PollMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.ReplyMarkupView
import org.monogram.presentation.features.chats.conversation.ui.message.StickerMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.TextMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.VenueMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.VideoMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.VideoNoteBubble
import org.monogram.presentation.features.chats.conversation.ui.message.VoiceMessageBubble

@Composable
internal fun MessageBubbleContainer(
    msg: MessageModel,
    newerMsg: MessageModel?,
    appearance: MessageAppearanceConfig,
    behavior: MessageRowBehaviorConfig,
    uiFlags: MessageRowUiFlags = MessageRowUiFlags(),
    senderGrouping: MessageSenderGrouping,
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
    onPositionChange: (Long, Offset, IntSize) -> Unit = { _, _, _ -> },
    toProfile: (Long) -> Unit,
    onForwardOriginClick: (ForwardInfo) -> Unit = {},
    onViaBotClick: (String) -> Unit = {},
    onReplySwipe: (MessageModel) -> Unit = {},
    downloadUtils: IDownloadUtils
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
    val topSpacing = if (!senderGrouping.isSameSenderAbove) 8.dp else 2.dp
    val dragOffsetX = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    val layoutTracker = remember { MessageBubbleLayoutTracker() }
    val onReplyClickState by rememberUpdatedState(onReplyClick)
    val onPositionChangeState by rememberUpdatedState(onPositionChange)
    val onReplySwipeState by rememberUpdatedState(onReplySwipe)

    val onBubbleClick: (Offset) -> Unit = remember(msg.id) {
        { offset ->
            onReplyClickState(
                layoutTracker.bubblePosition,
                layoutTracker.bubbleSize,
                layoutTracker.bubblePosition + offset
            )
        }
    }
    val onBubbleCenterClick: () -> Unit = remember(msg.id) {
        {
            onReplyClickState(
                layoutTracker.bubblePosition,
                layoutTracker.bubbleSize,
                layoutTracker.bubblePosition + (layoutTracker.bubbleSize.toSize() / 2f).toOffset()
            )
        }
    }

    MessageBubbleGestureLayer(
        modifier = Modifier.padding(top = topSpacing),
        canReply = behavior.canReply,
        swipeEnabled = behavior.swipeEnabled,
        dragOffsetX = dragOffsetX,
        scope = coroutineScope,
        maxWidth = maxWidth.value,
        onReplySwipe = { onReplySwipeState(msg) },
        layoutTracker = layoutTracker,
        onOutsideBubblePress = { clickPosition ->
            onReplyClickState(
                layoutTracker.bubblePosition,
                layoutTracker.bubbleSize,
                clickPosition
            )
        }
    ) {
        MessageBubbleShell(
            isOutgoing = isOutgoing,
            avatar = {
                MessageAvatar(
                    avatarPath = msg.senderAvatar,
                    fallbackPath = msg.senderPersonalAvatar,
                    senderName = msg.senderName,
                    senderId = msg.senderId,
                    isVisible = behavior.isGroup && !isOutgoing && !senderGrouping.isSameSenderBelow,
                    toProfile = toProfile
                )
                if (behavior.isGroup && !isOutgoing) {
                    Spacer(modifier = Modifier.width(8.dp))
                }
            },
            content = {
                Box(modifier = Modifier.wrapContentSize()) {
                    MessageBubbleContentHost(
                        modifier = Modifier
                            .width(IntrinsicSize.Max)
                            .widthIn(max = maxWidth)
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
                            },
                        msg = msg,
                        newerMsg = newerMsg,
                        isOutgoing = isOutgoing,
                        senderGrouping = senderGrouping,
                        isGroup = behavior.isGroup,
                        appearance = appearance,
                        onPhotoClick = onPhotoClick,
                        onDownloadPhoto = onDownloadPhoto,
                        onVideoClick = onVideoClick,
                        onDocumentClick = onDocumentClick,
                        onAudioClick = onAudioClick,
                        onCancelDownload = onCancelDownload,
                        onBubbleClick = onBubbleClick,
                        onBubbleLongClick = onBubbleClick,
                        onBubbleCenterLongClick = onBubbleCenterClick,
                        onGoToReply = onGoToReply,
                        onReactionClick = onReactionClick,
                        onStickerClick = onStickerClick,
                        onPollOptionClick = onPollOptionClick,
                        onRetractVote = onRetractVote,
                        onShowVoters = onShowVoters,
                        onClosePoll = onClosePoll,
                        onInstantViewClick = onInstantViewClick,
                        onYouTubeClick = onYouTubeClick,
                        onReplyMarkupButtonClick = onReplyMarkupButtonClick,
                        toProfile = toProfile,
                        onForwardOriginClick = onForwardOriginClick,
                        onViaBotClick = onViaBotClick,
                        downloadUtils = downloadUtils,
                        isAnyViewerOpen = behavior.isAnyViewerOpen
                    )

                    FastReplyIndicator(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        dragOffsetX = dragOffsetX,
                        isOutgoing = isOutgoing,
                        maxWidth = maxWidth
                    )
                }
            }
        )
    }
}

@Composable
private fun MessageBubbleGestureLayer(
    modifier: Modifier = Modifier,
    canReply: Boolean,
    swipeEnabled: Boolean,
    dragOffsetX: Animatable<Float, AnimationVector1D>,
    scope: kotlinx.coroutines.CoroutineScope,
    maxWidth: Float,
    onReplySwipe: () -> Unit,
    layoutTracker: MessageBubbleLayoutTracker,
    onOutsideBubblePress: (Offset) -> Unit,
    content: @Composable () -> Unit
) {
    val onOutsideBubblePressState by rememberUpdatedState(onOutsideBubblePress)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { layoutTracker.outerColumnPosition = it.positionInWindow() }
            .offset { IntOffset(dragOffsetX.value.toInt(), 0) }
            .fastReplyPointer(
                canReply = canReply && swipeEnabled,
                dragOffsetX = dragOffsetX,
                scope = scope,
                onReplySwipe = onReplySwipe,
                maxWidth = maxWidth
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        val clickPos = layoutTracker.outerColumnPosition + offset
                        val bubbleRect =
                            Rect(layoutTracker.bubblePosition, layoutTracker.bubbleSize.toSize())
                        if (!bubbleRect.contains(clickPos)) {
                            onOutsideBubblePressState(clickPos)
                        }
                    },
                    onLongPress = { offset ->
                        val clickPos = layoutTracker.outerColumnPosition + offset
                        val bubbleRect =
                            Rect(layoutTracker.bubblePosition, layoutTracker.bubbleSize.toSize())
                        if (!bubbleRect.contains(clickPos)) {
                            onOutsideBubblePressState(clickPos)
                        }
                    }
                )
            }
    ) {
        content()
    }
}

@Composable
private fun MessageBubbleShell(
    isOutgoing: Boolean,
    avatar: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        avatar()
        content()
    }
}

@Composable
private fun MessageBubbleContentHost(
    modifier: Modifier = Modifier,
    msg: MessageModel,
    newerMsg: MessageModel?,
    isOutgoing: Boolean,
    senderGrouping: MessageSenderGrouping,
    isGroup: Boolean,
    appearance: MessageAppearanceConfig,
    onPhotoClick: (MessageModel) -> Unit,
    onDownloadPhoto: (Int) -> Unit,
    onVideoClick: (MessageModel) -> Unit,
    onDocumentClick: (MessageModel) -> Unit,
    onAudioClick: (MessageModel) -> Unit,
    onCancelDownload: (Int) -> Unit,
    onBubbleClick: (Offset) -> Unit,
    onBubbleLongClick: (Offset) -> Unit,
    onBubbleCenterLongClick: () -> Unit,
    onGoToReply: (MessageModel) -> Unit,
    onReactionClick: (Long, String) -> Unit,
    onStickerClick: (Long) -> Unit,
    onPollOptionClick: (Long, Int) -> Unit,
    onRetractVote: (Long) -> Unit,
    onShowVoters: (Long, Int) -> Unit,
    onClosePoll: (Long) -> Unit,
    onInstantViewClick: ((String) -> Unit)?,
    onYouTubeClick: ((String) -> Unit)?,
    onReplyMarkupButtonClick: (Long, InlineKeyboardButtonModel) -> Unit,
    toProfile: (Long) -> Unit,
    onForwardOriginClick: (ForwardInfo) -> Unit,
    onViaBotClick: (String) -> Unit,
    downloadUtils: IDownloadUtils,
    isAnyViewerOpen: Boolean
) {
    Column(
        modifier = modifier,
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        MessageContentSelector(
            msg = msg,
            newerMsg = newerMsg,
            isOutgoing = isOutgoing,
            senderGrouping = senderGrouping,
            isGroup = isGroup,
            appearance = appearance,
            onPhotoClick = onPhotoClick,
            onDownloadPhoto = onDownloadPhoto,
            onVideoClick = onVideoClick,
            onDocumentClick = onDocumentClick,
            onAudioClick = onAudioClick,
            onCancelDownload = onCancelDownload,
            onBubbleClick = onBubbleClick,
            onBubbleLongClick = onBubbleLongClick,
            onBubbleCenterLongClick = onBubbleCenterLongClick,
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
            onForwardOriginClick = onForwardOriginClick,
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

@Composable
private fun MessageAvatar(
    avatarPath: String?,
    fallbackPath: String?,
    senderName: String,
    senderId: Long,
    isVisible: Boolean,
    toProfile: (Long) -> Unit
) {
    if (isVisible) {
        Avatar(
            path = avatarPath,
            fallbackPath = fallbackPath,
            name = senderName,
            size = 40.dp,
            isLocal = avatarPath?.contains("local") ?: false,
            onClick = { toProfile(senderId) }
        )
    } else {
        Spacer(modifier = Modifier.width(40.dp))
    }
}

@Composable
private fun MessageContentSelector(
    msg: MessageModel,
    newerMsg: MessageModel?,
    isOutgoing: Boolean,
    senderGrouping: MessageSenderGrouping,
    isGroup: Boolean,
    appearance: MessageAppearanceConfig,
    onPhotoClick: (MessageModel) -> Unit,
    onDownloadPhoto: (Int) -> Unit,
    onVideoClick: (MessageModel) -> Unit,
    onDocumentClick: (MessageModel) -> Unit,
    onAudioClick: (MessageModel) -> Unit,
    onCancelDownload: (Int) -> Unit,
    onBubbleClick: (Offset) -> Unit,
    onBubbleLongClick: (Offset) -> Unit,
    onBubbleCenterLongClick: () -> Unit,
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
    onForwardOriginClick: (ForwardInfo) -> Unit,
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
                    isSameSenderAbove = senderGrouping.isSameSenderAbove,
                    isSameSenderBelow = senderGrouping.isSameSenderBelow,
                    fontSize = appearance.fontSize,
                    letterSpacing = appearance.letterSpacing,
                    bubbleRadius = appearance.bubbleRadius,
                    isGroup = isGroup,
                    showLinkPreviews = appearance.showLinkPreviews,
                    onReplyClick = onGoToReply,
                    onReactionClick = { onReactionClick(msg.id, it) },
                    onInstantViewClick = onInstantViewClick,
                    onYouTubeClick = onYouTubeClick,
                    onClick = onBubbleClick,
                    onLongClick = onBubbleLongClick,
                    toProfile = toProfile,
                    onForwardOriginClick = onForwardOriginClick
                )
            }

            is MessageContent.Sticker -> {
                StickerMessageBubble(
                    content = content,
                    msg = msg,
                    isOutgoing = isOutgoing,
                    stickerSize = appearance.stickerSize,
                    onReplyClick = onGoToReply,
                    onReactionClick = { onReactionClick(msg.id, it) },
                    onStickerClick = { onStickerClick(it) },
                    onLongClick = onBubbleCenterLongClick
                )
            }

            is MessageContent.Photo -> {
                PhotoMessageBubble(
                    content = content,
                    msg = msg,
                    isOutgoing = isOutgoing,
                    isSameSenderAbove = senderGrouping.isSameSenderAbove,
                    isSameSenderBelow = senderGrouping.isSameSenderBelow,
                    fontSize = appearance.fontSize,
                    letterSpacing = appearance.letterSpacing,
                    isGroup = isGroup,
                    autoDownloadMobile = appearance.autoDownloadMobile,
                    autoDownloadWifi = appearance.autoDownloadWifi,
                    autoDownloadRoaming = appearance.autoDownloadRoaming,
                    onPhotoClick = onPhotoClick,
                    onDownloadPhoto = onDownloadPhoto,
                    onCancelDownload = onCancelDownload,
                    onLongClick = onBubbleLongClick,
                    onReplyClick = onGoToReply,
                    onReactionClick = { onReactionClick(msg.id, it) },
                    toProfile = toProfile,
                    onForwardOriginClick = onForwardOriginClick,
                    modifier = Modifier.fillMaxWidth(),
                    downloadUtils = downloadUtils
                )
            }

            is MessageContent.Video -> {
                VideoMessageBubble(
                    content = content,
                    msg = msg,
                    isOutgoing = isOutgoing,
                    isSameSenderAbove = senderGrouping.isSameSenderAbove,
                    isSameSenderBelow = senderGrouping.isSameSenderBelow,
                    fontSize = appearance.fontSize,
                    letterSpacing = appearance.letterSpacing,
                    autoDownloadMobile = appearance.autoDownloadMobile,
                    autoDownloadWifi = appearance.autoDownloadWifi,
                    autoDownloadRoaming = appearance.autoDownloadRoaming,
                    autoplayVideos = appearance.autoplayVideos,
                    onVideoClick = onVideoClick,
                    onCancelDownload = onCancelDownload,
                    onLongClick = onBubbleLongClick,
                    onReplyClick = onGoToReply,
                    onReactionClick = { onReactionClick(msg.id, it) },
                    toProfile = toProfile,
                    onForwardOriginClick = onForwardOriginClick,
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
                    onLongClick = onBubbleLongClick,
                    onReplyClick = onGoToReply,
                    onReactionClick = { onReactionClick(msg.id, it) }
                )
            }

            is MessageContent.Voice -> {
                VoiceMessageBubble(
                    content = content,
                    msg = msg,
                    isOutgoing = isOutgoing,
                    isSameSenderAbove = senderGrouping.isSameSenderAbove,
                    isSameSenderBelow = senderGrouping.isSameSenderBelow,
                    fontSize = appearance.fontSize,
                    letterSpacing = appearance.letterSpacing,
                    isGroup = isGroup,
                    autoDownloadFiles = appearance.autoDownloadFiles,
                    autoDownloadMobile = appearance.autoDownloadMobile,
                    autoDownloadWifi = appearance.autoDownloadWifi,
                    autoDownloadRoaming = appearance.autoDownloadRoaming,
                    onVoiceClick = onAudioClick,
                    onCancelDownload = onCancelDownload,
                    onLongClick = onBubbleLongClick,
                    onReplyClick = onGoToReply,
                    onReactionClick = { onReactionClick(msg.id, it) },
                    toProfile = toProfile,
                    onForwardOriginClick = onForwardOriginClick,
                    downloadUtils = downloadUtils
                )
            }

            is MessageContent.Gif -> {
                GifMessageBubble(
                    content = content,
                    msg = msg,
                    isOutgoing = isOutgoing,
                    isSameSenderAbove = senderGrouping.isSameSenderAbove,
                    isSameSenderBelow = senderGrouping.isSameSenderBelow,
                    fontSize = appearance.fontSize,
                    letterSpacing = appearance.letterSpacing,
                    autoDownloadMobile = appearance.autoDownloadMobile,
                    autoDownloadWifi = appearance.autoDownloadWifi,
                    autoDownloadRoaming = appearance.autoDownloadRoaming,
                    autoplayGifs = appearance.autoplayGifs,
                    onGifClick = onVideoClick,
                    onCancelDownload = onCancelDownload,
                    onLongClick = onBubbleLongClick,
                    onReplyClick = onGoToReply,
                    onReactionClick = { onReactionClick(msg.id, it) },
                    toProfile = toProfile,
                    onForwardOriginClick = onForwardOriginClick,
                    downloadUtils = downloadUtils,
                    isAnyViewerOpen = isAnyViewerOpen
                )
            }

            is MessageContent.Document -> {
                DocumentMessageBubble(
                    content = content,
                    msg = msg,
                    isOutgoing = isOutgoing,
                    isSameSenderAbove = senderGrouping.isSameSenderAbove,
                    isSameSenderBelow = senderGrouping.isSameSenderBelow,
                    fontSize = appearance.fontSize,
                    letterSpacing = appearance.letterSpacing,
                    isGroup = isGroup,
                    autoDownloadFiles = appearance.autoDownloadFiles,
                    autoDownloadMobile = appearance.autoDownloadMobile,
                    autoDownloadWifi = appearance.autoDownloadWifi,
                    autoDownloadRoaming = appearance.autoDownloadRoaming,
                    onDocumentClick = onDocumentClick,
                    onCancelDownload = onCancelDownload,
                    onLongClick = onBubbleLongClick,
                    onReplyClick = onGoToReply,
                    onReactionClick = { onReactionClick(msg.id, it) },
                    onForwardOriginClick = onForwardOriginClick,
                    downloadUtils = downloadUtils
                )
            }

            is MessageContent.Audio -> {
                AudioMessageBubble(
                    content = content,
                    msg = msg,
                    isOutgoing = isOutgoing,
                    isSameSenderAbove = senderGrouping.isSameSenderAbove,
                    isSameSenderBelow = senderGrouping.isSameSenderBelow,
                    fontSize = appearance.fontSize,
                    letterSpacing = appearance.letterSpacing,
                    isGroup = isGroup,
                    autoDownloadFiles = appearance.autoDownloadFiles,
                    autoDownloadMobile = appearance.autoDownloadMobile,
                    autoDownloadWifi = appearance.autoDownloadWifi,
                    autoDownloadRoaming = appearance.autoDownloadRoaming,
                    onAudioClick = onAudioClick,
                    onCancelDownload = onCancelDownload,
                    onLongClick = onBubbleLongClick,
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
                    isSameSenderAbove = senderGrouping.isSameSenderAbove,
                    isSameSenderBelow = senderGrouping.isSameSenderBelow,
                    fontSize = appearance.fontSize,
                    letterSpacing = appearance.letterSpacing,
                    bubbleRadius = appearance.bubbleRadius,
                    isGroup = isGroup,
                    onClick = { onGoToReply(msg) },
                    onLongClick = onBubbleCenterLongClick,
                    onReplyClick = onGoToReply,
                    onReactionClick = { onReactionClick(msg.id, it) },
                    toProfile = toProfile,
                    onForwardOriginClick = onForwardOriginClick,
                    showReactions = msg.reactions.isNotEmpty()
                )
            }

            is MessageContent.Poll -> {
                PollMessageBubble(
                    content = content,
                    msg = msg,
                    isOutgoing = isOutgoing,
                    isSameSenderAbove = senderGrouping.isSameSenderAbove,
                    isSameSenderBelow = senderGrouping.isSameSenderBelow,
                    fontSize = appearance.fontSize,
                    letterSpacing = appearance.letterSpacing,
                    bubbleRadius = appearance.bubbleRadius,
                    onOptionClick = { onPollOptionClick(msg.id, it) },
                    onRetractVote = { onRetractVote(msg.id) },
                    onLongClick = onBubbleLongClick,
                    onReplyClick = onGoToReply,
                    onReactionClick = { onReactionClick(msg.id, it) },
                    onShowVoters = { onShowVoters(msg.id, it) },
                    onClosePoll = { onClosePoll(msg.id) },
                    toProfile = toProfile,
                    onForwardOriginClick = onForwardOriginClick
                )
            }

            is MessageContent.Location -> {
                LocationMessageBubble(
                    content = content,
                    msg = msg,
                    isOutgoing = isOutgoing,
                    isSameSenderAbove = senderGrouping.isSameSenderAbove,
                    isSameSenderBelow = senderGrouping.isSameSenderBelow,
                    fontSize = appearance.fontSize,
                    letterSpacing = appearance.letterSpacing,
                    isGroup = isGroup,
                    bubbleRadius = appearance.bubbleRadius,
                    onClick = { onGoToReply(msg) },
                    onLongClick = onBubbleCenterLongClick,
                    onReplyClick = onGoToReply,
                    onReactionClick = { onReactionClick(msg.id, it) },
                    toProfile = toProfile,
                    onForwardOriginClick = onForwardOriginClick
                )
            }

            is MessageContent.Venue -> {
                VenueMessageBubble(
                    content = content,
                    msg = msg,
                    isOutgoing = isOutgoing,
                    isSameSenderAbove = senderGrouping.isSameSenderAbove,
                    isSameSenderBelow = senderGrouping.isSameSenderBelow,
                    fontSize = appearance.fontSize,
                    letterSpacing = appearance.letterSpacing,
                    isGroup = isGroup,
                    bubbleRadius = appearance.bubbleRadius,
                    onClick = { onGoToReply(msg) },
                    onLongClick = onBubbleCenterLongClick,
                    onReplyClick = onGoToReply,
                    onReactionClick = { onReactionClick(msg.id, it) },
                    toProfile = toProfile,
                    onForwardOriginClick = onForwardOriginClick
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

