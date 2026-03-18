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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.R
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.currentChat.ChatComponent
import org.monogram.presentation.features.chats.currentChat.chatContent.GroupedMessageItem
import org.monogram.presentation.features.chats.currentChat.chatContent.groupMessagesByAlbum
import org.monogram.presentation.features.chats.currentChat.chatContent.shouldShowDate
import org.monogram.presentation.features.chats.currentChat.components.*

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
    videoPlayerPool: VideoPlayerPool,
    isAnyViewerOpen: Boolean = false
) {
    val messages = state.allPinnedMessages
    val groupedMessages = remember(messages) { groupMessagesByAlbum(messages.distinctBy { it.id }) }
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.pinned_messages),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = pluralStringResource(R.plurals.pinned_count, messages.size, messages.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

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
                                    bubbleRadius = state.bubbleRadius,
                                    downloadUtils = downloadUtils,
                                    videoPlayerPool = videoPlayerPool
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
                                    downloadUtils = downloadUtils,
                                    videoPlayerPool = videoPlayerPool
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
                                    bubbleRadius = state.bubbleRadius,
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
                                    downloadUtils = downloadUtils,
                                    videoPlayerPool = videoPlayerPool
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
                                    downloadUtils = downloadUtils,
                                    videoPlayerPool = videoPlayerPool
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            Box(modifier = Modifier.padding(16.dp)) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(text = stringResource(R.string.pinned_close), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
