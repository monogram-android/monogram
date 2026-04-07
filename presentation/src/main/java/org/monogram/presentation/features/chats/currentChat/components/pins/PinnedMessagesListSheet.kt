package org.monogram.presentation.features.chats.currentChat.components.pins

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.rememberShimmerBrush
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.currentChat.chatContent.GroupedMessageItem
import org.monogram.presentation.features.chats.currentChat.chatContent.groupMessagesByAlbum
import org.monogram.presentation.features.chats.currentChat.chatContent.shouldShowDate
import org.monogram.presentation.features.chats.currentChat.components.AlbumMessageBubbleContainer
import org.monogram.presentation.features.chats.currentChat.components.ChannelMessageBubbleContainer
import org.monogram.presentation.features.chats.currentChat.components.DateSeparator
import org.monogram.presentation.features.chats.currentChat.components.MessageBubbleContainer
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinnedMessagesListSheet(
    isVisible: Boolean,
    allPinnedMessages: List<MessageModel>,
    pinnedMessageCount: Int,
    isLoadingPinnedMessages: Boolean,
    isGroup: Boolean,
    isChannel: Boolean,
    fontSize: Float,
    letterSpacing: Float,
    bubbleRadius: Float,
    stickerSize: Float,
    autoDownloadMobile: Boolean,
    autoDownloadWifi: Boolean,
    autoDownloadRoaming: Boolean,
    autoDownloadFiles: Boolean,
    autoplayGifs: Boolean,
    autoplayVideos: Boolean,
    onDismissRequest: () -> Unit,
    onHidden: () -> Unit,
    onMessageClick: (MessageModel) -> Unit,
    onUnpin: (MessageModel) -> Unit,
    onReplyClick: (MessageModel) -> Unit,
    onReactionClick: (Long, String) -> Unit,
    downloadUtils: IDownloadUtils,
    isAnyViewerOpen: Boolean = false
) {
    val messages = allPinnedMessages
    val groupedMessages = remember(messages) { groupMessagesByAlbum(messages.distinctBy { it.id }) }
    val showLoadingSkeleton = isLoadingPinnedMessages && messages.isEmpty()
    val displayedPinnedCount = maxOf(pinnedMessageCount, messages.size)
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val shimmerBrush = rememberShimmerBrush()
    var dismissOffsetY by remember { mutableFloatStateOf(0f) }
    var sheetHeightPx by remember { mutableFloatStateOf(0f) }
    var isAnimationReady by remember { mutableStateOf(false) }
    val dismissDistanceThresholdPx = with(density) { 104.dp.toPx() }
    val dismissVelocityThresholdPx = with(density) { 360.dp.toPx() }
    val statusBarTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val hiddenOffset = sheetHeightPx.takeIf { it > 0f } ?: with(density) { 640.dp.toPx() }
    val dismissProgress = (dismissOffsetY / hiddenOffset).coerceIn(0f, 1f)
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    val surfaceColor = MaterialTheme.colorScheme.background
    val contentColor = MaterialTheme.colorScheme.onSurface
    val scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.48f * (1f - dismissProgress))
    val scrimInteractionSource = remember { MutableInteractionSource() }
    val dragState = rememberDraggableState { delta ->
        if (isAnyViewerOpen) return@rememberDraggableState
        dismissOffsetY = (dismissOffsetY + delta).coerceAtLeast(0f)
    }

    LaunchedEffect(sheetHeightPx) {
        if (sheetHeightPx > 0f && !isAnimationReady) {
            dismissOffsetY = hiddenOffset
            isAnimationReady = true
        }
    }

    LaunchedEffect(isVisible, isAnimationReady, hiddenOffset) {
        if (!isAnimationReady) return@LaunchedEffect
        val target = if (isVisible) 0f else hiddenOffset
        animate(
            initialValue = dismissOffsetY,
            targetValue = target,
            animationSpec = if (isVisible) spring() else tween(durationMillis = 220)
        ) { value, _ -> dismissOffsetY = value }
        if (!isVisible) {
            onHidden()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scrimColor)
                .clickable(
                    interactionSource = scrimInteractionSource,
                    indication = null
                ) {
                    if (!isAnyViewerOpen) onDismissRequest()
                }
        )

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxSize()
                .padding(top = statusBarTopPadding)
                .offset { IntOffset(x = 0, y = dismissOffsetY.roundToInt()) }
                .onSizeChanged { sheetHeightPx = it.height.toFloat() },
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = surfaceColor,
            contentColor = contentColor
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .draggable(
                            state = dragState,
                            orientation = Orientation.Vertical,
                            enabled = !isAnyViewerOpen,
                            onDragStopped = { velocity ->
                                if (isAnyViewerOpen) {
                                    scope.launch {
                                        animate(
                                            initialValue = dismissOffsetY,
                                            targetValue = 0f,
                                            animationSpec = spring()
                                        ) { value, _ -> dismissOffsetY = value }
                                    }
                                    return@draggable
                                }

                                    val shouldDismiss =
                                        dismissOffsetY > dismissDistanceThresholdPx ||
                                                velocity > dismissVelocityThresholdPx

                                    if (shouldDismiss) {
                                        onDismissRequest()
                                    } else {
                                        scope.launch {
                                            animate(
                                            initialValue = dismissOffsetY,
                                            targetValue = 0f,
                                            animationSpec = spring()
                                        ) { value, _ -> dismissOffsetY = value }
                                    }
                                }
                            }
                        )
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    BottomSheetDefaults.DragHandle()

                    Text(
                        text = stringResource(R.string.pinned_messages),
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    if (showLoadingSkeleton) {
                        Box(
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .width(108.dp)
                                .height(18.dp)
                                .background(
                                    brush = shimmerBrush,
                                    shape = RoundedCornerShape(9.dp)
                                )
                        )
                    } else {
                        Text(
                            text = pluralStringResource(
                                R.plurals.pinned_count,
                                displayedPinnedCount,
                                displayedPinnedCount
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 4.dp),
                        color = dividerColor
                    )
                }

                if (showLoadingSkeleton) {
                    PinnedMessagesLoadingSkeleton(
                        brush = shimmerBrush,
                        isChannel = isChannel,
                        modifier = Modifier
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.05f))
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.05f)),
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        itemsIndexed(groupedMessages, key = { _, item ->
                            when (item) {
                                is GroupedMessageItem.Single -> "pin_${item.message.id}"
                                is GroupedMessageItem.Album -> "pin_album_${item.albumId}"
                            }
                        }) { index, item ->
                            val msg = when (item) {
                                is GroupedMessageItem.Single -> item.message
                                is GroupedMessageItem.Album -> item.messages.last()
                            }

                            val olderMsg = when (val olderItem = groupedMessages.getOrNull(index + 1)) {
                                is GroupedMessageItem.Single -> olderItem.message
                                is GroupedMessageItem.Album -> olderItem.messages.last()
                                null -> null
                            }

                            val newerMsg = when (val newerItem = groupedMessages.getOrNull(index - 1)) {
                                is GroupedMessageItem.Single -> newerItem.message
                                is GroupedMessageItem.Album -> newerItem.messages.first()
                                null -> null
                            }

                            if (shouldShowDate(msg, olderMsg)) {
                                DateSeparator(msg.date)
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            Box(modifier = Modifier.animateItem()) {
                                if (isChannel) {
                                    if (item is GroupedMessageItem.Single) {
                                        ChannelMessageBubbleContainer(
                                            msg = item.message,
                                            olderMsg = olderMsg,
                                            newerMsg = newerMsg,
                                            autoplayGifs = autoplayGifs,
                                            autoplayVideos = autoplayVideos,
                                            autoDownloadFiles = autoDownloadFiles,
                                            onPhotoClick = { onMessageClick(it) },
                                            onVideoClick = { onMessageClick(it) },
                                            onDocumentClick = { onMessageClick(it) },
                                            onReplyClick = { _, _, _ -> onMessageClick(item.message) },
                                            onGoToReply = { onReplyClick(it) },
                                            onReactionClick = onReactionClick,
                                            fontSize = fontSize,
                                            letterSpacing = letterSpacing,
                                            bubbleRadius = bubbleRadius,
                                            stickerSize = stickerSize,
                                            downloadUtils = downloadUtils
                                        )
                                    } else if (item is GroupedMessageItem.Album) {
                                        AlbumMessageBubbleContainer(
                                            messages = item.messages,
                                            olderMsg = olderMsg,
                                            newerMsg = newerMsg,
                                            isGroup = false,
                                            isChannel = true,
                                            autoplayGifs = autoplayGifs,
                                            autoplayVideos = autoplayVideos,
                                            onPhotoClick = { onMessageClick(it) },
                                            onVideoClick = { onMessageClick(it) },
                                            onReplyClick = { _, _, _ -> onMessageClick(item.messages.last()) },
                                            onGoToReply = { onReplyClick(it) },
                                            onReactionClick = onReactionClick,
                                            toProfile = {},
                                            downloadUtils = downloadUtils
                                        )
                                    }
                                } else {
                                    if (item is GroupedMessageItem.Single) {
                                        MessageBubbleContainer(
                                            msg = item.message,
                                            olderMsg = olderMsg,
                                            newerMsg = newerMsg,
                                            isGroup = isGroup,
                                            fontSize = fontSize,
                                            letterSpacing = letterSpacing,
                                            bubbleRadius = bubbleRadius,
                                            stSize = stickerSize,
                                            autoDownloadMobile = autoDownloadMobile,
                                            autoDownloadWifi = autoDownloadWifi,
                                            autoDownloadRoaming = autoDownloadRoaming,
                                            autoDownloadFiles = autoDownloadFiles,
                                            autoplayGifs = autoplayGifs,
                                            autoplayVideos = autoplayVideos,
                                            onPhotoClick = { onMessageClick(it) },
                                            onVideoClick = { onMessageClick(it) },
                                            onDocumentClick = { onMessageClick(it) },
                                            onReplyClick = { _, _, _ -> onMessageClick(item.message) },
                                            onGoToReply = { onReplyClick(it) },
                                            onReactionClick = onReactionClick,
                                            toProfile = {},
                                            downloadUtils = downloadUtils
                                        )
                                    } else if (item is GroupedMessageItem.Album) {
                                        AlbumMessageBubbleContainer(
                                            messages = item.messages,
                                            olderMsg = olderMsg,
                                            newerMsg = newerMsg,
                                            isGroup = isGroup,
                                            isChannel = false,
                                            autoplayGifs = autoplayGifs,
                                            autoplayVideos = autoplayVideos,
                                            onPhotoClick = { onMessageClick(it) },
                                            onVideoClick = { onMessageClick(it) },
                                            onReplyClick = { _, _, _ -> onMessageClick(item.messages.last()) },
                                            onGoToReply = { onReplyClick(it) },
                                            onReactionClick = onReactionClick,
                                            toProfile = {},
                                            downloadUtils = downloadUtils
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}

private data class PinnedSkeletonConfig(
    val isOutgoing: Boolean,
    val bubbleWidth: Float,
    val lineWidths: List<Float>
)

@Composable
private fun PinnedMessagesLoadingSkeleton(
    brush: Brush,
    isChannel: Boolean,
    modifier: Modifier = Modifier
) {
    val items = listOf(
        PinnedSkeletonConfig(false, 0.82f, listOf(0.92f, 0.64f)),
        PinnedSkeletonConfig(true, 0.58f, listOf(0.8f)),
        PinnedSkeletonConfig(false, 0.74f, listOf(0.88f, 0.7f)),
        PinnedSkeletonConfig(true, 0.62f, listOf(0.86f, 0.6f)),
        PinnedSkeletonConfig(false, 0.68f, listOf(0.76f)),
        PinnedSkeletonConfig(true, 0.8f, listOf(0.9f, 0.62f)),
        PinnedSkeletonConfig(false, 0.56f, listOf(0.72f))
    )

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(items) { _, item ->
            val outgoing = !isChannel && item.isOutgoing
            val bubbleColor = if (outgoing) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = bubbleColor,
                    modifier = Modifier.fillMaxWidth(item.bubbleWidth)
                ) {
                    Column(
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 9.dp, bottom = 7.dp)
                    ) {
                        item.lineWidths.forEachIndexed { index, width ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(width)
                                    .height(14.dp)
                                    .background(
                                        brush = brush,
                                        shape = RoundedCornerShape(5.dp)
                                    )
                            )
                            if (index != item.lineWidths.lastIndex) {
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }

                        Spacer(modifier = Modifier.height(7.dp))

                        Box(
                            modifier = Modifier
                                .align(Alignment.End)
                                .width(if (outgoing) 44.dp else 32.dp)
                                .height(10.dp)
                                .background(
                                    brush = brush,
                                    shape = RoundedCornerShape(3.dp)
                                )
                        )
                    }
                }
            }
        }
    }
}
