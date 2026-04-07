package org.monogram.presentation.features.chats.currentChat.components.pins

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.rememberShimmerBrush
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.currentChat.ChatComponent
import org.monogram.presentation.features.chats.currentChat.chatContent.GroupedMessageItem
import org.monogram.presentation.features.chats.currentChat.chatContent.groupMessagesByAlbum
import org.monogram.presentation.features.chats.currentChat.chatContent.shouldShowDate
import org.monogram.presentation.features.chats.currentChat.components.AlbumMessageBubbleContainer
import org.monogram.presentation.features.chats.currentChat.components.ChannelMessageBubbleContainer
import org.monogram.presentation.features.chats.currentChat.components.DateSeparator
import org.monogram.presentation.features.chats.currentChat.components.MessageBubbleContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinnedMessagesListSheet(
    state: ChatComponent.State,
    onDismiss: () -> Unit,
    onMessageClick: (MessageModel) -> Unit,
    onUnpin: (MessageModel) -> Unit,
    onReplyClick: (MessageModel) -> Unit,
    onReactionClick: (Long, String) -> Unit,
    downloadUtils: IDownloadUtils,
    isAnyViewerOpen: Boolean = false
) {
    val messages = state.allPinnedMessages
    val groupedMessages = remember(messages) { groupMessagesByAlbum(messages.distinctBy { it.id }) }
    val isLoadingPinnedMessages = state.isLoadingPinnedMessages && messages.isEmpty()
    val displayedPinnedCount = maxOf(state.pinnedMessageCount, messages.size)
    val shimmerBrush = rememberShimmerBrush()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
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
                if (isLoadingPinnedMessages) {
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
            }

            HorizontalDivider(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))

            if (isLoadingPinnedMessages) {
                PinnedMessagesLoadingSkeleton(
                    brush = shimmerBrush,
                    isChannel = state.isChannel,
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
                        if (state.isChannel) {
                            if (item is GroupedMessageItem.Single) {
                                ChannelMessageBubbleContainer(
                                    msg = item.message,
                                    olderMsg = olderMsg,
                                    newerMsg = newerMsg,
                                    autoplayGifs = state.autoplayGifs,
                                    autoplayVideos = state.autoplayVideos,
                                    autoDownloadFiles = state.autoDownloadFiles,
                                    onPhotoClick = { onMessageClick(it) },
                                    onVideoClick = { onMessageClick(it) },
                                    onDocumentClick = { onMessageClick(it) },
                                    onReplyClick = { _, _, _ -> onMessageClick(item.message) },
                                    onGoToReply = { onReplyClick(it) },
                                    onReactionClick = onReactionClick,
                                    fontSize = state.fontSize,
                                    letterSpacing = state.letterSpacing,
                                    bubbleRadius = state.bubbleRadius,
                                    stickerSize = state.stickerSize,
                                    canReply = false,
                                    downloadUtils = downloadUtils
                                )
                            } else if (item is GroupedMessageItem.Album) {
                                AlbumMessageBubbleContainer(
                                    messages = item.messages,
                                    olderMsg = olderMsg,
                                    newerMsg = newerMsg,
                                    isGroup = false,
                                    isChannel = true,
                                    autoplayGifs = state.autoplayGifs,
                                    autoplayVideos = state.autoplayVideos,
                                    onPhotoClick = { onMessageClick(it) },
                                    onVideoClick = { onMessageClick(it) },
                                    onReplyClick = { _, _, _ -> onMessageClick(item.messages.last()) },
                                    onGoToReply = { onReplyClick(it) },
                                    onReactionClick = onReactionClick,
                                    toProfile = {},
                                    canReply = false,
                                    downloadUtils = downloadUtils
                                )
                            }
                        } else {
                            if (item is GroupedMessageItem.Single) {
                                MessageBubbleContainer(
                                    msg = item.message,
                                    olderMsg = olderMsg,
                                    newerMsg = newerMsg,
                                    isGroup = state.isGroup,
                                    fontSize = state.fontSize,
                                    letterSpacing = state.letterSpacing,
                                    bubbleRadius = state.bubbleRadius,
                                    stSize = state.stickerSize,
                                    autoDownloadMobile = state.autoDownloadMobile,
                                    autoDownloadWifi = state.autoDownloadWifi,
                                    autoDownloadRoaming = state.autoDownloadRoaming,
                                    autoDownloadFiles = state.autoDownloadFiles,
                                    autoplayGifs = state.autoplayGifs,
                                    autoplayVideos = state.autoplayVideos,
                                    onPhotoClick = { onMessageClick(it) },
                                    onVideoClick = { onMessageClick(it) },
                                    onDocumentClick = { onMessageClick(it) },
                                    onReplyClick = { _, _, _ -> onMessageClick(item.message) },
                                    onGoToReply = { onReplyClick(it) },
                                    onReactionClick = onReactionClick,
                                    toProfile = {},
                                    canReply = false,
                                    downloadUtils = downloadUtils
                                )
                            } else if (item is GroupedMessageItem.Album) {
                                AlbumMessageBubbleContainer(
                                    messages = item.messages,
                                    olderMsg = olderMsg,
                                    newerMsg = newerMsg,
                                    isGroup = state.isGroup,
                                    isChannel = false,
                                    autoplayGifs = state.autoplayGifs,
                                    autoplayVideos = state.autoplayVideos,
                                    onPhotoClick = { onMessageClick(it) },
                                    onVideoClick = { onMessageClick(it) },
                                    onReplyClick = { _, _, _ -> onMessageClick(item.messages.last()) },
                                    onGoToReply = { onReplyClick(it) },
                                    onReactionClick = onReactionClick,
                                    toProfile = {},
                                    canReply = false,
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
