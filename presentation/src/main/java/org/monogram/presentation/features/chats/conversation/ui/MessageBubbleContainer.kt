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
import org.monogram.presentation.features.chats.conversation.ui.message.ChecklistMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.ContactMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.DocumentMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.GifMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.LinkPreviewAction
import org.monogram.presentation.features.chats.conversation.ui.message.LocationMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.MessageViaBotAttribution
import org.monogram.presentation.features.chats.conversation.ui.message.PaidMediaMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.PhotoMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.PollMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.ReplyMarkupView
import org.monogram.presentation.features.chats.conversation.ui.message.RichMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.StickerMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.TextMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.VenueMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.VideoMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.VideoNoteBubble
import org.monogram.presentation.features.chats.conversation.ui.message.VoiceMessageBubble
import org.monogram.presentation.features.chats.conversation.ui.message.prefersExpandedBubbleWidth

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
    onDownloadVideo: (Int) -> Unit = {},
    onDocumentClick: (MessageModel) -> Unit = {},
    onAudioClick: (MessageModel) -> Unit = {},
    onCancelDownload: (Int) -> Unit = {},
    onReplyClick: (Offset, IntSize, Offset) -> Unit,
    onMessageLongPress: ((Offset, IntSize, Offset) -> Unit)? = null,
    onGoToReply: (MessageModel) -> Unit = {},
    onReactionClick: (Long, String) -> Unit = { _, _ -> },
    onStickerClick: (Long) -> Unit = {},
    onDownloadSticker: (Int) -> Unit = {},
    onPollOptionClick: (Long, Int) -> Unit = { _, _ -> },
    onRetractVote: (Long) -> Unit = {},
    onShowVoters: (Long, Int) -> Unit = { _, _ -> },
    onClosePoll: (Long) -> Unit = {},
    onLinkPreviewAction: ((LinkPreviewAction) -> Unit)? = null,
    onReplyMarkupButtonClick: (Long, InlineKeyboardButtonModel) -> Unit = { _, _ -> },
    onPositionChange: (Long, Offset, IntSize) -> Unit = { _, _, _ -> },
    toProfile: (Long) -> Unit,
    onForwardOriginClick: (ForwardInfo) -> Unit = {},
    onViaBotClick: (String) -> Unit = {},
    onReplySwipe: (MessageModel) -> Unit = {},
    onChecklistTaskToggle: (Long, Int, Boolean) -> Unit = { _, _, _ -> },
    onChecklistEdit: (MessageModel) -> Unit = {},
    onPaidMediaBuy: (MessageModel) -> Unit = {},
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
    val onMessageLongPressState by rememberUpdatedState(onMessageLongPress)
    val onPositionChangeState by rememberUpdatedState(onPositionChange)
    val selectionBadgeAlignment = if (isOutgoing) {
        MessageSelectionBadgeAlignment.TopEnd
    } else {
        MessageSelectionBadgeAlignment.TopStart
    }
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
    remember(msg.id) {
        {
            onReplyClickState(
                layoutTracker.bubblePosition,
                layoutTracker.bubbleSize,
                layoutTracker.bubblePosition + (layoutTracker.bubbleSize.toSize() / 2f).toOffset()
            )
        }
    }
    val onBubbleLongClick: (Offset) -> Unit = remember(msg.id) {
        { offset ->
            (onMessageLongPressState ?: onReplyClickState)(
                layoutTracker.bubblePosition,
                layoutTracker.bubbleSize,
                layoutTracker.bubblePosition + offset
            )
        }
    }
    val onBubbleCenterLongClick: () -> Unit = remember(msg.id) {
        {
            (onMessageLongPressState ?: onReplyClickState)(
                layoutTracker.bubblePosition,
                layoutTracker.bubbleSize,
                layoutTracker.bubblePosition + (layoutTracker.bubbleSize.toSize() / 2f).toOffset()
            )
        }
    }
    val contentPrefersExpandedWidth = remember(msg.content) {
        msg.content.prefersExpandedBubbleWidth()
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
        onOutsideBubbleTap = { clickPosition ->
            onReplyClickState(
                layoutTracker.bubblePosition,
                layoutTracker.bubbleSize,
                clickPosition
            )
        },
        onOutsideBubbleLongPress = { clickPosition ->
            (onMessageLongPressState ?: onReplyClickState)(
                layoutTracker.bubblePosition,
                layoutTracker.bubbleSize,
                clickPosition
            )
        }
    ) {
        MessageBubbleShell(
            isOutgoing = isOutgoing,
            avatar = {
                if (behavior.isGroup && !isOutgoing) {
                    MessageAvatar(
                        avatarPath = msg.senderAvatar,
                        fallbackPath = msg.senderPersonalAvatar,
                        senderName = msg.senderName,
                        senderId = msg.senderId,
                        isVisible = !senderGrouping.isSameSenderBelow,
                        toProfile = toProfile
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
            },
            content = {
                Box(modifier = Modifier.wrapContentSize()) {
                    MessageSelectionDecoration(
                        isSelectionMode = behavior.isSelectionMode,
                        isSelected = uiFlags.isSelected,
                        badgeAlignment = selectionBadgeAlignment,
                        modifier = Modifier
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
                        MessageBubbleContentHost(
                            modifier = Modifier
                                .then(
                                    if (contentPrefersExpandedWidth) {
                                        Modifier.fillMaxWidth()
                                    } else {
                                        Modifier.width(IntrinsicSize.Max)
                                    }
                                )
                                .widthIn(max = maxWidth),
                            msg = msg,
                            newerMsg = newerMsg,
                            isOutgoing = isOutgoing,
                            senderGrouping = senderGrouping,
                            isGroup = behavior.isGroup,
                            appearance = appearance,
                            onPhotoClick = onPhotoClick,
                            onDownloadPhoto = onDownloadPhoto,
                            onVideoClick = onVideoClick,
                            onDownloadVideo = onDownloadVideo,
                            onDocumentClick = onDocumentClick,
                            onAudioClick = onAudioClick,
                            onCancelDownload = onCancelDownload,
                            onBubbleClick = onBubbleClick,
                            onBubbleLongClick = onBubbleLongClick,
                            onBubbleCenterLongClick = onBubbleCenterLongClick,
                            onGoToReply = onGoToReply,
                            onReactionClick = onReactionClick,
                            onStickerClick = onStickerClick,
                            onDownloadSticker = onDownloadSticker,
                            onPollOptionClick = onPollOptionClick,
                            onRetractVote = onRetractVote,
                            onShowVoters = onShowVoters,
                            onClosePoll = onClosePoll,
                            onLinkPreviewAction = onLinkPreviewAction,
                            onReplyMarkupButtonClick = onReplyMarkupButtonClick,
                            toProfile = toProfile,
                            onForwardOriginClick = onForwardOriginClick,
                            onViaBotClick = onViaBotClick,
                            onChecklistTaskToggle = onChecklistTaskToggle,
                            onChecklistEdit = onChecklistEdit,
                            onPaidMediaBuy = onPaidMediaBuy,
                            downloadUtils = downloadUtils,
                            isAnyViewerOpen = behavior.isAnyViewerOpen
                        )
                    }

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
    onOutsideBubbleTap: (Offset) -> Unit,
    onOutsideBubbleLongPress: (Offset) -> Unit,
    content: @Composable () -> Unit
) {
    val onOutsideBubbleTapState by rememberUpdatedState(onOutsideBubbleTap)
    val onOutsideBubbleLongPressState by rememberUpdatedState(onOutsideBubbleLongPress)

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
                            onOutsideBubbleTapState(clickPos)
                        }
                    },
                    onLongPress = { offset ->
                        val clickPos = layoutTracker.outerColumnPosition + offset
                        val bubbleRect =
                            Rect(layoutTracker.bubblePosition, layoutTracker.bubbleSize.toSize())
                        if (!bubbleRect.contains(clickPos)) {
                            onOutsideBubbleLongPressState(clickPos)
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
    onDownloadVideo: (Int) -> Unit,
    onDocumentClick: (MessageModel) -> Unit,
    onAudioClick: (MessageModel) -> Unit,
    onCancelDownload: (Int) -> Unit,
    onBubbleClick: (Offset) -> Unit,
    onBubbleLongClick: (Offset) -> Unit,
    onBubbleCenterLongClick: () -> Unit,
    onGoToReply: (MessageModel) -> Unit,
    onReactionClick: (Long, String) -> Unit,
    onStickerClick: (Long) -> Unit,
    onDownloadSticker: (Int) -> Unit,
    onPollOptionClick: (Long, Int) -> Unit,
    onRetractVote: (Long) -> Unit,
    onShowVoters: (Long, Int) -> Unit,
    onClosePoll: (Long) -> Unit,
    onLinkPreviewAction: ((LinkPreviewAction) -> Unit)?,
    onReplyMarkupButtonClick: (Long, InlineKeyboardButtonModel) -> Unit,
    toProfile: (Long) -> Unit,
    onForwardOriginClick: (ForwardInfo) -> Unit,
    onViaBotClick: (String) -> Unit,
    onChecklistTaskToggle: (Long, Int, Boolean) -> Unit,
    onChecklistEdit: (MessageModel) -> Unit,
    onPaidMediaBuy: (MessageModel) -> Unit,
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
            onDownloadVideo = onDownloadVideo,
            onDocumentClick = onDocumentClick,
            onAudioClick = onAudioClick,
            onCancelDownload = onCancelDownload,
            onBubbleClick = onBubbleClick,
            onBubbleLongClick = onBubbleLongClick,
            onBubbleCenterLongClick = onBubbleCenterLongClick,
            onGoToReply = onGoToReply,
            onReactionClick = onReactionClick,
            onStickerClick = onStickerClick,
            onDownloadSticker = onDownloadSticker,
            onPollOptionClick = onPollOptionClick,
            onRetractVote = onRetractVote,
            onShowVoters = onShowVoters,
            onClosePoll = onClosePoll,
            onLinkPreviewAction = onLinkPreviewAction,
            toProfile = toProfile,
            onForwardOriginClick = onForwardOriginClick,
            onChecklistTaskToggle = onChecklistTaskToggle,
            onChecklistEdit = onChecklistEdit,
            onPaidMediaBuy = onPaidMediaBuy,
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
    onDownloadVideo: (Int) -> Unit,
    onDocumentClick: (MessageModel) -> Unit,
    onAudioClick: (MessageModel) -> Unit,
    onCancelDownload: (Int) -> Unit,
    onBubbleClick: (Offset) -> Unit,
    onBubbleLongClick: (Offset) -> Unit,
    onBubbleCenterLongClick: () -> Unit,
    onGoToReply: (MessageModel) -> Unit,
    onReactionClick: (Long, String) -> Unit,
    onStickerClick: (Long) -> Unit,
    onDownloadSticker: (Int) -> Unit,
    onPollOptionClick: (Long, Int) -> Unit,
    onRetractVote: (Long) -> Unit,
    onShowVoters: (Long, Int) -> Unit,
    onClosePoll: (Long) -> Unit,
    onLinkPreviewAction: ((LinkPreviewAction) -> Unit)?,
    toProfile: (Long) -> Unit,
    onForwardOriginClick: (ForwardInfo) -> Unit,
    onChecklistTaskToggle: (Long, Int, Boolean) -> Unit,
    onChecklistEdit: (MessageModel) -> Unit,
    onPaidMediaBuy: (MessageModel) -> Unit,
    downloadUtils: IDownloadUtils,
    isAnyViewerOpen: Boolean = false
) {
    val finalMsg = remember(msg, appearance.showReactions) {
        if (appearance.showReactions) msg else msg.copy(reactions = emptyList())
    }
    val contentPrefersExpandedWidth = remember(finalMsg.content) {
        finalMsg.content.prefersExpandedBubbleWidth()
    }

    Column(
        modifier = if (contentPrefersExpandedWidth) {
            Modifier.fillMaxWidth()
        } else {
            Modifier.width(IntrinsicSize.Max)
        },
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        when (val content = finalMsg.content) {
            is MessageContent.Text -> {
                TextMessageBubble(
                    content = content,
                    msg = finalMsg,
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
                    onLinkPreviewAction = onLinkPreviewAction,
                    onLinkPreviewLongClick = onBubbleCenterLongClick,
                    onClick = onBubbleClick,
                    onLongClick = onBubbleLongClick,
                    toProfile = toProfile,
                    onForwardOriginClick = onForwardOriginClick
                )
            }

            is MessageContent.RichMessage -> {
                RichMessageBubble(
                    content = content,
                    msg = msg,
                    isOutgoing = isOutgoing,
                    isSameSenderAbove = senderGrouping.isSameSenderAbove,
                    isSameSenderBelow = senderGrouping.isSameSenderBelow,
                    fontSize = appearance.fontSize,
                    isGroup = isGroup,
                    bubbleRadius = appearance.bubbleRadius,
                    onReplyClick = onGoToReply,
                    onReactionClick = { onReactionClick(msg.id, it) },
                    onClick = onBubbleClick,
                    onLongClick = onBubbleLongClick,
                    onLinkPreviewAction = onLinkPreviewAction,
                    toProfile = toProfile,
                    onForwardOriginClick = onForwardOriginClick
                )
            }

            is MessageContent.Sticker -> {
                StickerMessageBubble(
                    content = content,
                    msg = finalMsg,
                    isOutgoing = isOutgoing,
                    stickerSize = appearance.stickerSize,
                    onReplyClick = onGoToReply,
                    onReactionClick = { onReactionClick(msg.id, it) },
                    onStickerClick = { onStickerClick(it) },
                    onDownloadSticker = onDownloadSticker,
                    onLongClick = onBubbleCenterLongClick
                )
            }

            is MessageContent.Photo -> {
                PhotoMessageBubble(
                    content = content,
                    msg = finalMsg,
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
                    msg = finalMsg,
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
                    onDownloadVideo = onDownloadVideo,
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
                    msg = finalMsg,
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
                    msg = finalMsg,
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
                    msg = finalMsg,
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
                    modifier = Modifier.fillMaxWidth(),
                    downloadUtils = downloadUtils,
                    isAnyViewerOpen = isAnyViewerOpen
                )
            }

            is MessageContent.Document -> {
                DocumentMessageBubble(
                    content = content,
                    msg = finalMsg,
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
                    msg = finalMsg,
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
                    msg = finalMsg,
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
                    msg = finalMsg,
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
                    msg = finalMsg,
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

            is MessageContent.Checklist -> {
                ChecklistMessageBubble(
                    content = content,
                    msg = msg,
                    isOutgoing = isOutgoing,
                    isSameSenderAbove = senderGrouping.isSameSenderAbove,
                    isSameSenderBelow = senderGrouping.isSameSenderBelow,
                    fontSize = appearance.fontSize,
                    letterSpacing = appearance.letterSpacing,
                    onReplyClick = onGoToReply,
                    onReactionClick = { onReactionClick(msg.id, it) },
                    onTaskToggle = { taskId, isDone ->
                        onChecklistTaskToggle(
                            msg.id,
                            taskId,
                            isDone
                        )
                    },
                    onLongClick = onBubbleCenterLongClick,
                    onOpenEditor = { onChecklistEdit(msg) },
                    toProfile = toProfile,
                    onForwardOriginClick = onForwardOriginClick,
                    isGroup = isGroup
                )
            }

            is MessageContent.PaidMedia -> {
                PaidMediaMessageBubble(
                    content = content,
                    msg = msg,
                    isOutgoing = isOutgoing,
                    isSameSenderAbove = senderGrouping.isSameSenderAbove,
                    isSameSenderBelow = senderGrouping.isSameSenderBelow,
                    onPhotoClick = onPhotoClick,
                    onVideoClick = onVideoClick,
                    onOpenBuy = { onPaidMediaBuy(msg) },
                    onLongClick = onBubbleCenterLongClick,
                    toProfile = toProfile,
                    onForwardOriginClick = onForwardOriginClick,
                    isGroup = isGroup
                )
            }

            is MessageContent.Venue -> {
                VenueMessageBubble(
                    content = content,
                    msg = finalMsg,
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

