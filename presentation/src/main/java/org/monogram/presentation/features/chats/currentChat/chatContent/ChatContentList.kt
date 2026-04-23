package org.monogram.presentation.features.chats.currentChat.chatContent

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.monogram.domain.models.ForwardInfo
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.TopicModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.currentChat.ChatComponent
import org.monogram.presentation.features.chats.currentChat.components.AlbumMessageBubbleContainer
import org.monogram.presentation.features.chats.currentChat.components.DateSeparator
import org.monogram.presentation.features.chats.currentChat.components.MessageBubbleContainer
import org.monogram.presentation.features.chats.currentChat.components.ServiceMessage
import org.monogram.presentation.features.chats.currentChat.components.UnreadMessagesSeparator
import org.monogram.presentation.features.chats.currentChat.components.channels.ChannelMessageBubbleContainer
import org.monogram.presentation.features.stickers.ui.view.StickerImage
import java.io.File

@Immutable
data class ChatMessageListUiState(
    val chatId: Long,
    val currentTopicId: Long?,
    val messages: List<MessageModel>,
    val selectedMessageIds: Set<Long>,
    val unreadSeparatorCount: Int,
    val unreadSeparatorLastReadInboxMessageId: Long,
    val viewAsTopics: Boolean,
    val topics: List<TopicModel>,
    val rootMessage: MessageModel?,
    val isLoading: Boolean,
    val isLoadingOlder: Boolean,
    val isLoadingNewer: Boolean,
    val isAtBottom: Boolean,
    val isLatestLoaded: Boolean,
    val isOldestLoaded: Boolean,
    val isGroup: Boolean,
    val isChannel: Boolean,
    val isAdmin: Boolean,
    val canWrite: Boolean,
    val canSendAnything: Boolean,
    val highlightedMessageId: Long?,
    val fontSize: Float,
    val letterSpacing: Float,
    val bubbleRadius: Float,
    val stickerSize: Float,
    val autoDownloadMobile: Boolean,
    val autoDownloadWifi: Boolean,
    val autoDownloadRoaming: Boolean,
    val autoDownloadFiles: Boolean,
    val autoplayGifs: Boolean,
    val autoplayVideos: Boolean,
    val showLinkPreviews: Boolean,
    val isChatAnimationsEnabled: Boolean,
    val suppressEntryAnimations: Boolean
) {
    val isComments: Boolean
        get() = rootMessage != null

    val isForumList: Boolean
        get() = viewAsTopics && currentTopicId == null

    val isCurrentTopicClosed: Boolean
        get() = topics.find { it.id.toLong() == currentTopicId }?.isClosed == true

    val isChannelFeed: Boolean
        get() = isChannel && currentTopicId == null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatContentList(
    state: ChatMessageListUiState,
    component: ChatComponent,
    scrollState: LazyListState,
    groupedMessages: List<GroupedMessageItem>,
    onPhotoClick: (MessageModel, List<String>, List<String?>, List<Long>, Int) -> Unit,
    onPhotoDownload: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showNavPadding: Boolean = false,
    topOverlayPadding: Dp = 0.dp,
    onVideoClick: (MessageModel, String?, String?) -> Unit,
    onDocumentClick: (MessageModel) -> Unit,
    onAudioClick: (MessageModel) -> Unit,
    onMessageOptionsClick: (MessageModel, Offset, IntSize, Offset) -> Unit,
    onGoToReply: (MessageModel) -> Unit,
    selectedMessageId: Long? = null,
    onMessagePositionChange: (Offset, IntSize) -> Unit = { _, _ -> },
    onViaBotClick: (String) -> Unit = {},
    toProfile: (Long) -> Unit,
    onForwardOriginClick: (ForwardInfo) -> Unit,
    downloadUtils: IDownloadUtils,
    isAnyViewerOpen: Boolean = false,
    bottomContentPadding: Dp = 8.dp
) {
    val isComments = state.isComments
    val isScrolling by remember(scrollState) { derivedStateOf { scrollState.isScrollInProgress } }
    val latestState by rememberUpdatedState(state)
    var lastOlderLoadTriggerUptimeMs by remember { mutableLongStateOf(0L) }
    var lastNewerLoadTriggerUptimeMs by remember { mutableLongStateOf(0L) }
    val loadTriggerThrottleMs = 350L
    val groupedMessageIds =
        remember(groupedMessages) { groupedMessages.map(GroupedMessageItem::firstMessageId) }
    var hasSeededEntryAnimations by rememberSaveable(
        state.chatId,
        state.currentTopicId
    ) { mutableStateOf(false) }
    val seenMessageIds =
        remember(state.chatId, state.currentTopicId) { mutableStateMapOf<Long, Boolean>() }
    val pendingEntryAnimationIds =
        remember(state.chatId, state.currentTopicId) { mutableStateMapOf<Long, Boolean>() }
    val unreadBoundaryIndex = remember(
        isComments,
        groupedMessages,
        state.messages,
        state.unreadSeparatorCount,
        state.unreadSeparatorLastReadInboxMessageId
    ) {
        if (isComments || state.unreadSeparatorCount <= 0) {
            null // suppress in thread/comments mode
        } else {
            val boundaryItem = findFirstUnreadBoundary(
                messages = state.messages,
                groupedItems = groupedMessages,
                firstUnreadMessageId = state.unreadSeparatorLastReadInboxMessageId
            )
            boundaryItem?.let { target ->
                groupedMessages.indexOfFirst { it.firstMessageId == target.firstMessageId }
                    .takeIf { it >= 0 }
            }
        }
    }
    var hasUnreadSeparatorBeenVisible by rememberSaveable(
        state.chatId,
        state.currentTopicId,
        unreadBoundaryIndex,
        state.unreadSeparatorLastReadInboxMessageId,
        state.unreadSeparatorCount
    ) { mutableStateOf(false) }
    var hasUnreadSeparatorDismissed by rememberSaveable(
        state.chatId,
        state.currentTopicId,
        unreadBoundaryIndex,
        state.unreadSeparatorLastReadInboxMessageId,
        state.unreadSeparatorCount
    ) { mutableStateOf(false) }

    LaunchedEffect(groupedMessageIds, state.suppressEntryAnimations) {
        val currentIds = groupedMessageIds.toSet()
        seenMessageIds.keys.toList().forEach { id ->
            if (id !in currentIds) {
                seenMessageIds.remove(id)
            }
        }
        pendingEntryAnimationIds.keys.toList().forEach { id ->
            if (id !in currentIds) {
                pendingEntryAnimationIds.remove(id)
            }
        }

        if (groupedMessageIds.isEmpty()) {
            if (state.suppressEntryAnimations || state.isLoading) {
                hasSeededEntryAnimations = false
            }
            return@LaunchedEffect
        }

        if (!hasSeededEntryAnimations || state.suppressEntryAnimations) {
            groupedMessageIds.forEach { id -> seenMessageIds[id] = true }
            pendingEntryAnimationIds.clear()
            hasSeededEntryAnimations = true
            return@LaunchedEffect
        }

        groupedMessageIds.forEach { id ->
            if (seenMessageIds.put(id, true) == null) {
                pendingEntryAnimationIds[id] = true
            }
        }
    }


    LaunchedEffect(
        scrollState,
        groupedMessages.size,
        isComments
    ) {
        snapshotFlow { scrollState.layoutInfo.visibleItemsInfo }
            .filter { it.isNotEmpty() && groupedMessages.isNotEmpty() }
            .map { visibleItems ->
                val firstIndex = visibleItems.first().index
                val lastIndex = visibleItems.last().index
                firstIndex to lastIndex
            }
            .distinctUntilChanged()
            .collect { (firstVisibleIndex, lastVisibleIndex) ->
                val currentState = latestState
                if (currentState.isLoading || currentState.isLoadingOlder || currentState.isLoadingNewer) return@collect

                val nearStart = firstVisibleIndex <= 2
                val nearEnd = lastVisibleIndex >= (groupedMessages.size - 3).coerceAtLeast(0)
                val now = SystemClock.uptimeMillis()

                if (isComments) {
                    if (!scrollState.isScrollInProgress) return@collect

                    if (nearStart && !currentState.isOldestLoaded) {
                        if (now - lastOlderLoadTriggerUptimeMs >= loadTriggerThrottleMs) {
                            lastOlderLoadTriggerUptimeMs = now
                            component.loadMore()
                        }
                    } else if (nearEnd && !currentState.isLatestLoaded) {
                        if (now - lastNewerLoadTriggerUptimeMs >= loadTriggerThrottleMs) {
                            lastNewerLoadTriggerUptimeMs = now
                            component.loadNewer()
                        }
                    }
                } else {
                    if (nearEnd && !currentState.isOldestLoaded) {
                        if (now - lastOlderLoadTriggerUptimeMs >= loadTriggerThrottleMs) {
                            lastOlderLoadTriggerUptimeMs = now
                            component.loadMore()
                        }
                    } else if (nearStart && !currentState.isAtBottom && !currentState.isLatestLoaded) {
                        if (now - lastNewerLoadTriggerUptimeMs >= loadTriggerThrottleMs) {
                            lastNewerLoadTriggerUptimeMs = now
                            component.loadNewer()
                        }
                    }
                }
            }
    }

    LaunchedEffect(
        scrollState,
        unreadBoundaryIndex,
        isComments,
        showNavPadding,
        state.isLoadingOlder,
        state.isLoadingNewer,
        state.isAtBottom,
        groupedMessages.isNotEmpty()
    ) {
        val boundaryIndex = unreadBoundaryIndex ?: return@LaunchedEffect
        snapshotFlow { scrollState.layoutInfo.visibleItemsInfo }
            .filter { it.isNotEmpty() }
            .map { visibleItems ->
                val leadingItems = chatContentLeadingItemsCount(
                    isComments = isComments,
                    showNavPadding = showNavPadding,
                    isLoadingOlder = state.isLoadingOlder,
                    isLoadingNewer = state.isLoadingNewer,
                    isAtBottom = state.isAtBottom,
                    hasMessages = groupedMessages.isNotEmpty()
                )
                visibleItems.any { item ->
                    lazyIndexToGroupedIndex(item.index, leadingItems) == boundaryIndex
                }
            }
            .distinctUntilChanged()
            .collect { isBoundaryVisible ->
                if (isBoundaryVisible) {
                    hasUnreadSeparatorBeenVisible = true
                } else if (hasUnreadSeparatorBeenVisible) {
                    hasUnreadSeparatorDismissed = true
                }
            }
    }

    if (state.viewAsTopics && state.currentTopicId == null) {
        TopicsList(
            topics = state.topics,
            onTopicClick = { component.onTopicClick(it.id) },
            topOverlayPadding = topOverlayPadding,
            modifier = modifier
        )
        return
    }

    LazyColumn(
        state = scrollState,
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = "ChatMessages" },
        reverseLayout = !isComments,
        contentPadding = PaddingValues(
            top = if (isComments) topOverlayPadding + 8.dp else 8.dp,
            bottom = bottomContentPadding
        )
    ) {
        if (isComments && state.isLoadingOlder && groupedMessages.isNotEmpty()) {
            item(key = "loading_older_top") {
                PagingLoadingIndicator()
            }
        }

        if (!isComments && state.isLoadingNewer && !state.isAtBottom && groupedMessages.isNotEmpty()) {
            item(key = "loading_newer_bottom") {
                PagingLoadingIndicator()
            }
        }

        if (isComments) {
            item(key = "root_header") {
                RootMessageSection(
                    state,
                    component,
                    onPhotoClick,
                    onPhotoDownload,
                    onVideoClick,
                    onDocumentClick,
                    onAudioClick,
                    onMessageOptionsClick,
                    onGoToReply,
                    onViaBotClick,
                    toProfile,
                    onForwardOriginClick,
                    downloadUtils,
                    isAnyViewerOpen = isAnyViewerOpen
                )
            }

            itemsIndexed(
                items = groupedMessages,
                key = { _, item ->
                    when (item) {
                        is GroupedMessageItem.Single -> "msg_${item.message.id}"
                        is GroupedMessageItem.Album -> "album_${item.albumId}"
                    }
                },
                contentType = { _, item ->
                    when (item) {
                        is GroupedMessageItem.Single -> "single"
                        is GroupedMessageItem.Album -> "album"
                    }
                }
            ) { index, item ->
                val olderMsg = remember(groupedMessages, index) { getMessageAt(groupedMessages, index - 1) }
                val newerMsg = remember(groupedMessages, index) { getMessageAt(groupedMessages, index + 1) }

                MessageRowItem(
                    item = item,
                    state = state,
                    component = component,
                    olderMsg = olderMsg,
                    newerMsg = newerMsg,
                    isSelected = isItemSelected(item, state.selectedMessageIds),
                    isSelectionMode = state.selectedMessageIds.isNotEmpty(),
                    selectedMessageId = selectedMessageId,
                    onPhotoClick = onPhotoClick,
                    onPhotoDownload = onPhotoDownload,
                    onVideoClick = onVideoClick,
                    onDocumentClick = onDocumentClick,
                    onAudioClick = onAudioClick,
                    onMessageOptionsClick = onMessageOptionsClick,
                    onGoToReply = onGoToReply,
                    onMessagePositionChange = onMessagePositionChange,
                    onViaBotClick = onViaBotClick,
                    toProfile = toProfile,
                    onForwardOriginClick = onForwardOriginClick,
                    isScrolling = isScrolling,
                    isEntryAnimationPending = pendingEntryAnimationIds.containsKey(item.firstMessageId),
                    onEntryAnimationConsumed = { pendingEntryAnimationIds.remove(it) },
                    downloadUtils = downloadUtils,
                    isAnyViewerOpen = isAnyViewerOpen,
                    showUnreadSeparator = index == unreadBoundaryIndex && !hasUnreadSeparatorDismissed,
                    unreadCount = state.unreadSeparatorCount
                )
            }
        } else {
            if (showNavPadding) {
                item {
                    Spacer(
                        modifier = Modifier.height(
                            WindowInsets.navigationBars
                                .asPaddingValues()
                                .calculateBottomPadding()
                        )
                    )
                }
            }
            itemsIndexed(
                items = groupedMessages,
                key = { _, item ->
                    when (item) {
                        is GroupedMessageItem.Single -> "msg_${item.message.id}"
                        is GroupedMessageItem.Album -> "album_${item.albumId}"
                    }
                },
                contentType = { _, item ->
                    when (item) {
                        is GroupedMessageItem.Single -> "single"
                        is GroupedMessageItem.Album -> "album"
                    }
                }
            ) { index, item ->
                val olderMsg = remember(groupedMessages, index) { getMessageAt(groupedMessages, index + 1) }
                val newerMsg = remember(groupedMessages, index) { getMessageAt(groupedMessages, index - 1) }

                MessageRowItem(
                    item = item,
                    state = state,
                    component = component,
                    olderMsg = olderMsg,
                    newerMsg = newerMsg,
                    isSelected = isItemSelected(item, state.selectedMessageIds),
                    isSelectionMode = state.selectedMessageIds.isNotEmpty(),
                    selectedMessageId = selectedMessageId,
                    onPhotoClick = onPhotoClick,
                    onPhotoDownload = onPhotoDownload,
                    onVideoClick = onVideoClick,
                    onDocumentClick = onDocumentClick,
                    onAudioClick = onAudioClick,
                    onMessageOptionsClick = onMessageOptionsClick,
                    onGoToReply = onGoToReply,
                    onMessagePositionChange = onMessagePositionChange,
                    onViaBotClick = onViaBotClick,
                    toProfile = toProfile,
                    onForwardOriginClick = onForwardOriginClick,
                    isScrolling = isScrolling,
                    isEntryAnimationPending = pendingEntryAnimationIds.containsKey(item.firstMessageId),
                    onEntryAnimationConsumed = { pendingEntryAnimationIds.remove(it) },
                    downloadUtils = downloadUtils,
                    isAnyViewerOpen = isAnyViewerOpen,
                    showUnreadSeparator = index == unreadBoundaryIndex && !hasUnreadSeparatorDismissed,
                    unreadCount = state.unreadSeparatorCount
                )
            }
        }

        if (isComments && state.isLoadingNewer && groupedMessages.isNotEmpty()) {
            item(key = "loading_newer_bottom") {
                PagingLoadingIndicator()
            }
        }

        if (!isComments && state.isLoadingOlder && groupedMessages.isNotEmpty()) {
            item(key = "loading_older_top") {
                PagingLoadingIndicator()
            }
        }

        if (state.isLoading && groupedMessages.isNotEmpty() && !state.isLoadingOlder && !state.isLoadingNewer) {
            item(key = "loading_indicator") {
                PagingLoadingIndicator()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PagingLoadingIndicator() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LoadingIndicator(
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = stringResource(R.string.loading_text),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MessageRowItem(
    item: GroupedMessageItem,
    state: ChatMessageListUiState,
    component: ChatComponent,
    olderMsg: MessageModel?,
    newerMsg: MessageModel?,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    selectedMessageId: Long?,
    onPhotoClick: (MessageModel, List<String>, List<String?>, List<Long>, Int) -> Unit,
    onPhotoDownload: (Int) -> Unit,
    onVideoClick: (MessageModel, String?, String?) -> Unit,
    onDocumentClick: (MessageModel) -> Unit,
    onAudioClick: (MessageModel) -> Unit,
    onMessageOptionsClick: (MessageModel, Offset, IntSize, Offset) -> Unit,
    onGoToReply: (MessageModel) -> Unit,
    onMessagePositionChange: (Offset, IntSize) -> Unit,
    onViaBotClick: (String) -> Unit,
    toProfile: (Long) -> Unit,
    onForwardOriginClick: (ForwardInfo) -> Unit,
    isScrolling: Boolean,
    isEntryAnimationPending: Boolean,
    onEntryAnimationConsumed: (Long) -> Unit,
    downloadUtils: IDownloadUtils,
    isAnyViewerOpen: Boolean = false,
    showUnreadSeparator: Boolean,
    unreadCount: Int
) {
    val mainMsg = remember(item) {
        if (item is GroupedMessageItem.Single) item.message else (item as GroupedMessageItem.Album).messages.last()
    }

    val shouldAnimateEntry =
        state.isChatAnimationsEnabled && isEntryAnimationPending && !isScrolling

    val scale = remember(mainMsg.id) {
        Animatable(
            if (shouldAnimateEntry) 0.98f else 1f
        )
    }
    val itemAlpha = remember(mainMsg.id) {
        Animatable(
            if (shouldAnimateEntry) 0f else 1f
        )
    }
    val offsetY = remember(mainMsg.id) {
        Animatable(
            if (shouldAnimateEntry) 10f else 0f
        )
    }

    LaunchedEffect(mainMsg.id) {
        component.onMessageVisible(mainMsg.id)
    }

    LaunchedEffect(mainMsg.id, shouldAnimateEntry) {
        if (shouldAnimateEntry && scale.value < 1f) {
            val stiffness = Spring.StiffnessMediumLow
            launch { scale.animateTo(1f, spring(Spring.DampingRatioLowBouncy, stiffness)) }
            launch { itemAlpha.animateTo(1f, spring(stiffness = stiffness)) }
            launch { offsetY.animateTo(0f, spring(stiffness = Spring.StiffnessLow)) }
            onEntryAnimationConsumed(mainMsg.id)
        } else {
            scale.snapTo(1f)
            itemAlpha.snapTo(1f)
            offsetY.snapTo(0f)
            if (isEntryAnimationPending) {
                onEntryAnimationConsumed(mainMsg.id)
            }
        }
    }

    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
        label = "bg"
    )
    val horizontalPadding by animateDpAsState(if (isSelectionMode) 16.dp else 8.dp, label = "padding")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                alpha = itemAlpha.value
                translationY = offsetY.value
            }
            .background(backgroundColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = isSelectionMode,
                onClick = { component.onToggleMessageSelection(mainMsg.id) }
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = 1.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            AnimatedVisibility(visible = isSelectionMode, enter = expandHorizontally(), exit = shrinkHorizontally()) {
                SelectionIndicator(isSelected = isSelected, modifier = Modifier.padding(end = 12.dp, bottom = 4.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                if (shouldShowDate(mainMsg, olderMsg)) {
                    DateSeparator(mainMsg.date)
                    Spacer(modifier = Modifier.height(16.dp))
                }

                AnimatedVisibility(visible = showUnreadSeparator && !isScrolling) {
                    Column {
                        UnreadMessagesSeparator(unreadCount = unreadCount)
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                MessageBubbleSwitcher(
                    item = item,
                    state = state,
                    component = component,
                    olderMsg = olderMsg,
                    newerMsg = newerMsg,
                    isSelectionMode = isSelectionMode,
                    selectedMessageId = selectedMessageId,
                    onPhotoClick = onPhotoClick,
                    onPhotoDownload = onPhotoDownload,
                    onVideoClick = onVideoClick,
                    onDocumentClick = onDocumentClick,
                    onAudioClick = onAudioClick,
                    onMessageOptionsClick = onMessageOptionsClick,
                    onGoToReply = onGoToReply,
                    onMessagePositionChange = onMessagePositionChange,
                    onViaBotClick = onViaBotClick,
                    toProfile = toProfile,
                    onForwardOriginClick = onForwardOriginClick,
                    downloadUtils = downloadUtils,
                    isAnyViewerOpen = isAnyViewerOpen
                )
            }
        }
    }
}

@Composable
private fun MessageBubbleSwitcher(
    item: GroupedMessageItem,
    state: ChatMessageListUiState,
    component: ChatComponent,
    olderMsg: MessageModel?,
    newerMsg: MessageModel?,
    isSelectionMode: Boolean,
    selectedMessageId: Long?,
    onPhotoClick: (MessageModel, List<String>, List<String?>, List<Long>, Int) -> Unit,
    onPhotoDownload: (Int) -> Unit,
    onVideoClick: (MessageModel, String?, String?) -> Unit,
    onDocumentClick: (MessageModel) -> Unit,
    onAudioClick: (MessageModel) -> Unit,
    onMessageOptionsClick: (MessageModel, Offset, IntSize, Offset) -> Unit,
    onGoToReply: (MessageModel) -> Unit,
    onMessagePositionChange: (Offset, IntSize) -> Unit,
    onViaBotClick: (String) -> Unit,
    toProfile: (Long) -> Unit,
    onForwardOriginClick: (ForwardInfo) -> Unit,
    downloadUtils: IDownloadUtils,
    isAnyViewerOpen: Boolean = false
) {
    val isChannel = state.isChannelFeed
    val isTopicClosed = state.isCurrentTopicClosed
    val sanitizedItem = remember(item, state.rootMessage) {
        item.withSuppressedRootReply(state.rootMessage?.id)
    }
    val sanitizedOlderMsg = remember(olderMsg, state.rootMessage) {
        olderMsg?.suppressRootReply(state.rootMessage?.id)
    }
    val sanitizedNewerMsg = remember(newerMsg, state.rootMessage) {
        newerMsg?.suppressRootReply(state.rootMessage?.id)
    }

    when (sanitizedItem) {
        is GroupedMessageItem.Single -> {
            if (sanitizedItem.message.content is MessageContent.Service) {
                ServiceMessage(service = sanitizedItem.message.content as MessageContent.Service)
            } else if (isChannel) {
                ChannelMessageBubbleContainer(
                    msg = sanitizedItem.message,
                    olderMsg = sanitizedOlderMsg,
                    newerMsg = sanitizedNewerMsg,
                    autoplayGifs = state.autoplayGifs,
                    autoplayVideos = state.autoplayVideos,
                    autoDownloadFiles = state.autoDownloadFiles,
                    highlighted = state.highlightedMessageId == sanitizedItem.message.id,
                    onHighlightConsumed = { component.onHighlightConsumed() },
                    onPhotoClick = {
                        if (isSelectionMode) component.onToggleMessageSelection(it.id) else handlePhotoClick(
                            it,
                            onPhotoClick
                        )
                    },
                    onDownloadPhoto = onPhotoDownload,
                    onVideoClick = {
                        if (isSelectionMode) component.onToggleMessageSelection(it.id) else handleVideoClick(
                            it,
                            onVideoClick
                        )
                    },
                    onDocumentClick = {
                        if (isSelectionMode) component.onToggleMessageSelection(it.id) else onDocumentClick(
                            it
                        )
                    },
                    onAudioClick = {
                        if (isSelectionMode) component.onToggleMessageSelection(it.id) else onAudioClick(
                            it
                        )
                    },
                    onCancelDownload = { component.onCancelDownloadFile(it) },
                    onReplyClick = { pos, size, click ->
                        if (isSelectionMode) component.onToggleMessageSelection(sanitizedItem.message.id) else onMessageOptionsClick(
                            sanitizedItem.message,
                            pos,
                            size,
                            click
                        )
                    },
                    onGoToReply = onGoToReply,
                    onReactionClick = { id, r ->
                        if (isSelectionMode) component.onToggleMessageSelection(id) else component.onSendReaction(
                            id,
                            r
                        )
                    },
                    onReplyMarkupButtonClick = { id, btn ->
                        component.onReplyMarkupButtonClick(
                            id,
                            btn,
                            sanitizedItem.message.senderId
                        )
                    },
                    onStickerClick = {
                        if (isSelectionMode) component.onToggleMessageSelection(sanitizedItem.message.id) else component.onStickerClick(
                            it
                        )
                    },
                    onPollOptionClick = { id, opt ->
                        if (isSelectionMode) component.onToggleMessageSelection(id) else component.onPollOptionClick(
                            id,
                            opt
                        )
                    },
                    onRetractVote = {
                        if (isSelectionMode) component.onToggleMessageSelection(it) else component.onRetractVote(
                            it
                        )
                    },
                    onShowVoters = { id, opt ->
                        if (isSelectionMode) component.onToggleMessageSelection(id) else component.onShowVoters(
                            id,
                            opt
                        )
                    },
                    onClosePoll = {
                        if (isSelectionMode) component.onToggleMessageSelection(it) else component.onClosePoll(
                            it
                        )
                    },
                    fontSize = state.fontSize,
                    letterSpacing = state.letterSpacing,
                    bubbleRadius = state.bubbleRadius,
                    stickerSize = state.stickerSize,
                    shouldReportPosition = sanitizedItem.message.id == selectedMessageId,
                    onPositionChange = { _, pos, size -> onMessagePositionChange(pos, size) },
                    onCommentsClick = { component.onCommentsClick(it) },
                    toProfile = toProfile,
                    onForwardOriginClick = onForwardOriginClick,
                    onViaBotClick = onViaBotClick,
                    canReply = state.canWrite && !isSelectionMode && state.canSendAnything,
                    onReplySwipe = { component.onReplyMessage(it) },
                    onYouTubeClick = { component.onOpenYouTube(it) },
                    onInstantViewClick = { component.onOpenInstantView(it) },
                    downloadUtils = downloadUtils,
                    isAnyViewerOpen = isAnyViewerOpen
                )
            } else {
                MessageBubbleContainer(
                    msg = sanitizedItem.message,
                    olderMsg = sanitizedOlderMsg,
                    newerMsg = sanitizedNewerMsg,
                    isGroup = state.isGroup || state.currentTopicId != null,
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
                    showLinkPreviews = state.showLinkPreviews,
                    highlighted = state.highlightedMessageId == sanitizedItem.message.id,
                    onHighlightConsumed = { component.onHighlightConsumed() },
                    onPhotoClick = {
                        if (isSelectionMode) component.onToggleMessageSelection(it.id) else handlePhotoClick(
                            it,
                            onPhotoClick
                        )
                    },
                    onDownloadPhoto = onPhotoDownload,
                    onVideoClick = {
                        if (isSelectionMode) component.onToggleMessageSelection(it.id) else handleVideoClick(
                            it,
                            onVideoClick
                        )
                    },
                    onDocumentClick = {
                        if (isSelectionMode) component.onToggleMessageSelection(it.id) else onDocumentClick(
                            it
                        )
                    },
                    onAudioClick = {
                        if (isSelectionMode) component.onToggleMessageSelection(it.id) else onAudioClick(
                            it
                        )
                    },
                    onCancelDownload = { component.onCancelDownloadFile(it) },
                    onReplyClick = { pos, size, click ->
                        if (isSelectionMode) component.onToggleMessageSelection(sanitizedItem.message.id) else onMessageOptionsClick(
                            sanitizedItem.message,
                            pos,
                            size,
                            click
                        )
                    },
                    onGoToReply = onGoToReply,
                    onReactionClick = { id, r ->
                        if (isSelectionMode) component.onToggleMessageSelection(id) else component.onSendReaction(
                            id,
                            r
                        )
                    },
                    onReplyMarkupButtonClick = { id, btn ->
                        component.onReplyMarkupButtonClick(
                            id,
                            btn,
                            sanitizedItem.message.senderId
                        )
                    },
                    onStickerClick = {
                        if (isSelectionMode) component.onToggleMessageSelection(sanitizedItem.message.id) else component.onStickerClick(
                            it
                        )
                    },
                    onPollOptionClick = { id, opt ->
                        if (isSelectionMode) component.onToggleMessageSelection(id) else component.onPollOptionClick(
                            id,
                            opt
                        )
                    },
                    onRetractVote = {
                        if (isSelectionMode) component.onToggleMessageSelection(it) else component.onRetractVote(
                            it
                        )
                    },
                    onShowVoters = { id, opt ->
                        if (isSelectionMode) component.onToggleMessageSelection(id) else component.onShowVoters(
                            id,
                            opt
                        )
                    },
                    onClosePoll = {
                        if (isSelectionMode) component.onToggleMessageSelection(it) else component.onClosePoll(
                            it
                        )
                    },
                    onInstantViewClick = { component.onOpenInstantView(it) },
                    onYouTubeClick = { component.onOpenYouTube(it) },
                    shouldReportPosition = sanitizedItem.message.id == selectedMessageId,
                    onPositionChange = { _, pos, size -> onMessagePositionChange(pos, size) },
                    toProfile = toProfile,
                    onForwardOriginClick = onForwardOriginClick,
                    onViaBotClick = onViaBotClick,
                    canReply = state.canWrite && !isSelectionMode && (!isTopicClosed || state.isAdmin) && state.canSendAnything,
                    onReplySwipe = { component.onReplyMessage(it) },
                    swipeEnabled = !isSelectionMode,
                    downloadUtils = downloadUtils,
                    isAnyViewerOpen = isAnyViewerOpen
                )
            }
        }

        is GroupedMessageItem.Album -> {
            AlbumMessageBubbleContainer(
                messages = sanitizedItem.messages,
                olderMsg = sanitizedOlderMsg,
                newerMsg = sanitizedNewerMsg,
                isGroup = state.isGroup || state.currentTopicId != null,
                isChannel = isChannel,
                autoplayGifs = state.autoplayGifs,
                autoplayVideos = state.autoplayVideos,
                autoDownloadMobile = state.autoDownloadMobile,
                autoDownloadWifi = state.autoDownloadWifi,
                autoDownloadRoaming = state.autoDownloadRoaming,
                onPhotoClick = {
                    if (isSelectionMode) component.onToggleMessageSelection(it.id) else handleAlbumPhotoClick(
                        it,
                        sanitizedItem.messages,
                        onPhotoClick
                    )
                },
                onDownloadPhoto = onPhotoDownload,
                onVideoClick = {
                    if (isSelectionMode) component.onToggleMessageSelection(it.id) else handleAlbumVideoClick(
                        it,
                        sanitizedItem.messages,
                        onPhotoClick,
                        onVideoClick
                    )
                },
                onDocumentClick = {
                    if (isSelectionMode) component.onToggleMessageSelection(it.id) else onDocumentClick(
                        it
                    )
                },
                onAudioClick = {
                    if (isSelectionMode) component.onToggleMessageSelection(it.id) else onAudioClick(
                        it
                    )
                },
                onCancelDownload = { component.onCancelDownloadFile(it) },
                onReplyClick = { pos, size, click ->
                    if (isSelectionMode) component.onToggleMessageSelection(sanitizedItem.messages.last().id) else onMessageOptionsClick(
                        sanitizedItem.messages.last(),
                        pos,
                        size,
                        click
                    )
                },
                onGoToReply = onGoToReply,
                onReactionClick = { id, r ->
                    if (isSelectionMode) component.onToggleMessageSelection(id) else component.onSendReaction(
                        id,
                        r
                    )
                },
                shouldReportPosition = sanitizedItem.messages.last().id == selectedMessageId,
                onPositionChange = { _, pos, size -> onMessagePositionChange(pos, size) },
                onCommentsClick = { component.onCommentsClick(it) },
                toProfile = toProfile,
                onForwardOriginClick = onForwardOriginClick,
                onViaBotClick = onViaBotClick,
                canReply = state.canWrite && !isSelectionMode && (!isTopicClosed || state.isAdmin) && state.canSendAnything,
                onReplySwipe = { component.onReplyMessage(it) },
                swipeEnabled = !isSelectionMode,
                downloadUtils = downloadUtils,
                isAnyViewerOpen = isAnyViewerOpen
            )
        }
    }
}

@Composable
private fun SelectionIndicator(isSelected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Icon(
                Icons.Default.CheckCircle,
                null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun RootMessageSection(
    state: ChatMessageListUiState,
    component: ChatComponent,
    onPhotoClick: (MessageModel, List<String>, List<String?>, List<Long>, Int) -> Unit,
    onPhotoDownload: (Int) -> Unit,
    onVideoClick: (MessageModel, String?, String?) -> Unit,
    onDocumentClick: (MessageModel) -> Unit,
    onAudioClick: (MessageModel) -> Unit,
    onMessageOptionsClick: (MessageModel, Offset, IntSize, Offset) -> Unit,
    onGoToReply: (MessageModel) -> Unit,
    onViaBotClick: (String) -> Unit,
    toProfile: (Long) -> Unit,
    onForwardOriginClick: (ForwardInfo) -> Unit,
    downloadUtils: IDownloadUtils,
    isAnyViewerOpen: Boolean = false
) {
    val root = state.rootMessage ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
    ) {
        if (state.isChannel) {
            ChannelMessageBubbleContainer(
                msg = root, olderMsg = null, newerMsg = null,
                autoplayGifs = state.autoplayGifs, autoplayVideos = state.autoplayVideos,
                autoDownloadFiles = state.autoDownloadFiles,
                onPhotoClick = { handlePhotoClick(it, onPhotoClick) },
                onDownloadPhoto = onPhotoDownload,
                onVideoClick = { handleVideoClick(it, onVideoClick) },
                onDocumentClick = onDocumentClick,
                onAudioClick = onAudioClick,
                onCancelDownload = { component.onCancelDownloadFile(it) },
                onReplyClick = { pos, size, click -> onMessageOptionsClick(root, pos, size, click) },
                onGoToReply = onGoToReply,
                onReactionClick = { id, r -> component.onSendReaction(id, r) },
                onReplyMarkupButtonClick = { id, btn -> component.onReplyMarkupButtonClick(id, btn, root.senderId) },
                onStickerClick = { component.onStickerClick(it) },
                onPollOptionClick = { id, opt -> component.onPollOptionClick(id, opt) },
                onRetractVote = { component.onRetractVote(it) },
                onShowVoters = { id, opt -> component.onShowVoters(id, opt) },
                onClosePoll = { component.onClosePoll(it) },
                fontSize = state.fontSize,
                letterSpacing = state.letterSpacing,
                bubbleRadius = state.bubbleRadius,
                stickerSize = state.stickerSize,
                onCommentsClick = {}, showComments = false,
                toProfile = toProfile,
                onForwardOriginClick = onForwardOriginClick,
                onViaBotClick = onViaBotClick,
                onYouTubeClick = { component.onOpenYouTube(it) },
                onInstantViewClick = { component.onOpenInstantView(it) },
                downloadUtils = downloadUtils,
                isAnyViewerOpen = isAnyViewerOpen
            )
        } else {
            MessageBubbleContainer(
                msg = root, olderMsg = null, newerMsg = null, isGroup = state.isGroup,
                fontSize = state.fontSize,
                letterSpacing = state.letterSpacing,
                bubbleRadius = state.bubbleRadius,
                stSize = state.stickerSize,
                autoDownloadMobile = state.autoDownloadMobile, autoDownloadWifi = state.autoDownloadWifi,
                autoDownloadRoaming = state.autoDownloadRoaming, autoDownloadFiles = state.autoDownloadFiles,
                autoplayGifs = state.autoplayGifs, autoplayVideos = state.autoplayVideos,
                onPhotoClick = { handlePhotoClick(it, onPhotoClick) },
                onDownloadPhoto = onPhotoDownload,
                onVideoClick = { handleVideoClick(it, onVideoClick) },
                onDocumentClick = onDocumentClick,
                onAudioClick = onAudioClick,
                onCancelDownload = { component.onCancelDownloadFile(it) },
                onReplyClick = { pos, size, click -> onMessageOptionsClick(root, pos, size, click) },
                onGoToReply = onGoToReply,
                onReactionClick = { id, r -> component.onSendReaction(id, r) },
                onReplyMarkupButtonClick = { id, btn -> component.onReplyMarkupButtonClick(id, btn, root.senderId) },
                onStickerClick = { component.onStickerClick(it) },
                onPollOptionClick = { id, opt -> component.onPollOptionClick(id, opt) },
                onRetractVote = { component.onRetractVote(it) },
                onShowVoters = { id, opt -> component.onShowVoters(id, opt) },
                onClosePoll = { component.onClosePoll(it) },
                toProfile = toProfile,
                onForwardOriginClick = onForwardOriginClick,
                swipeEnabled = false,
                onViaBotClick = onViaBotClick,
                onInstantViewClick = { component.onOpenInstantView(it) },
                onYouTubeClick = { component.onOpenYouTube(it) },
                downloadUtils = downloadUtils,
                isAnyViewerOpen = isAnyViewerOpen
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    }
}

private fun getMessageAt(list: List<GroupedMessageItem>, index: Int): MessageModel? {
    return when (val item = list.getOrNull(index)) {
        is GroupedMessageItem.Single -> item.message
        is GroupedMessageItem.Album -> item.messages.last()
        null -> null
    }
}

private fun isItemSelected(item: GroupedMessageItem, selectedIds: Set<Long>): Boolean {
    return when (item) {
        is GroupedMessageItem.Single -> selectedIds.contains(item.message.id)
        is GroupedMessageItem.Album -> item.messages.any { selectedIds.contains(it.id) }
    }
}

private fun GroupedMessageItem.withSuppressedRootReply(rootMessageId: Long?): GroupedMessageItem {
    return when (this) {
        is GroupedMessageItem.Single -> copy(message = message.suppressRootReply(rootMessageId))
        is GroupedMessageItem.Album -> copy(messages = messages.map {
            it.suppressRootReply(
                rootMessageId
            )
        })
    }
}

private fun MessageModel.suppressRootReply(rootMessageId: Long?): MessageModel {
    if (rootMessageId == null || replyToMsgId != rootMessageId) return this
    return copy(
        replyToMsgId = null,
        replyToMsg = null
    )
}

internal fun chatContentLeadingItemsCount(
    isComments: Boolean,
    showNavPadding: Boolean,
    isLoadingOlder: Boolean,
    isLoadingNewer: Boolean,
    isAtBottom: Boolean,
    hasMessages: Boolean
): Int {
    return if (isComments) {
        val loadingOlderTop = if (isLoadingOlder && hasMessages) 1 else 0
        loadingOlderTop + 1 // root header
    } else {
        val navPadding = if (showNavPadding) 1 else 0
        val loadingNewerBottom = if (isLoadingNewer && !isAtBottom && hasMessages) 1 else 0
        navPadding + loadingNewerBottom
    }
}

internal fun groupedIndexToLazyIndex(
    groupedIndex: Int,
    leadingItemsCount: Int
): Int {
    return groupedIndex + leadingItemsCount
}

internal fun lazyIndexToGroupedIndex(
    lazyIndex: Int,
    leadingItemsCount: Int
): Int {
    return lazyIndex - leadingItemsCount
}

private fun handlePhotoClick(
    msg: MessageModel,
    onPhotoClick: (MessageModel, List<String>, List<String?>, List<Long>, Int) -> Unit
) {
    val path = msg.displayMediaPath() ?: return
    onPhotoClick(msg, listOf(path), listOf(msg.mediaCaption()), listOf(msg.id), 0)
}

private fun handleVideoClick(msg: MessageModel, onVideoClick: (MessageModel, String?, String?) -> Unit) {
    when (val content = msg.content) {
        is MessageContent.Video -> onVideoClick(msg, content.path, content.caption)
        is MessageContent.Gif -> onVideoClick(msg, content.path, content.caption)
        else -> {}
    }
}

private fun handleAlbumPhotoClick(
    clickedMsg: MessageModel,
    messages: List<MessageModel>,
    onPhotoClick: (MessageModel, List<String>, List<String?>, List<Long>, Int) -> Unit
) {
    val entries = buildAlbumMediaEntries(messages)
    if (entries.isEmpty()) {
        val singlePath = clickedMsg.displayMediaPath() ?: return
        onPhotoClick(clickedMsg, listOf(singlePath), listOf(clickedMsg.mediaCaption()), listOf(clickedMsg.id), 0)
        return
    }

    val index = entries.indexOfFirst { it.message.id == clickedMsg.id }
    if (index < 0) {
        val singlePath = clickedMsg.displayMediaPath() ?: return
        onPhotoClick(clickedMsg, listOf(singlePath), listOf(clickedMsg.mediaCaption()), listOf(clickedMsg.id), 0)
        return
    }

    onPhotoClick(
        clickedMsg,
        entries.map { it.path },
        entries.map { it.caption },
        entries.map { it.message.id },
        index
    )
}

private fun handleAlbumVideoClick(
    clickedMsg: MessageModel,
    messages: List<MessageModel>,
    onPhotoClick: (MessageModel, List<String>, List<String?>, List<Long>, Int) -> Unit,
    onVideoClick: (MessageModel, String?, String?) -> Unit
) {
    val videoContent = clickedMsg.content as? MessageContent.Video
    val gifContent = clickedMsg.content as? MessageContent.Gif
    val supportsStreaming = videoContent?.supportsStreaming ?: false
    val path = (videoContent?.path ?: gifContent?.path)?.takeIf { it.isNotBlank() && File(it).exists() }
    val clickedCaption = clickedMsg.mediaCaption()

    if (path == null && !supportsStreaming) return
    if (path == null && supportsStreaming) {
        onVideoClick(clickedMsg, null, clickedCaption)
        return
    }

    val entries = buildAlbumMediaEntries(messages)
    if (entries.isEmpty()) {
        onVideoClick(clickedMsg, path, clickedCaption)
        return
    }

    val index = entries.indexOfFirst { it.message.id == clickedMsg.id }
    if (index < 0) {
        onVideoClick(clickedMsg, path, clickedCaption)
        return
    }

    onPhotoClick(
        clickedMsg,
        entries.map { it.path },
        entries.map { it.caption },
        entries.map { it.message.id },
        index
    )

    if (supportsStreaming) {
        onVideoClick(clickedMsg, path, entries.getOrNull(index)?.caption)
    }
}

private data class AlbumMediaEntry(
    val message: MessageModel,
    val path: String,
    val caption: String?
)

private fun buildAlbumMediaEntries(messages: List<MessageModel>): List<AlbumMediaEntry> {
    return messages.mapNotNull { msg ->
        val path = msg.displayMediaPath() ?: return@mapNotNull null
        val caption = msg.mediaCaption()

        AlbumMediaEntry(message = msg, path = path, caption = caption)
    }
}

private fun MessageModel.displayMediaPath(): String? {
    val raw = when (val content = content) {
        is MessageContent.Photo -> content.path ?: content.thumbnailPath
        is MessageContent.Video -> content.path
        is MessageContent.Gif -> content.path
        else -> null
    }

    return raw?.takeIf { it.isNotBlank() && File(it).exists() }
}

private fun MessageModel.mediaCaption(): String? {
    return when (val content = content) {
        is MessageContent.Photo -> content.caption
        is MessageContent.Video -> content.caption
        is MessageContent.Gif -> content.caption
        else -> null
    }
}


@Composable
fun TopicsList(
    topics: List<TopicModel>,
    onTopicClick: (TopicModel) -> Unit,
    topOverlayPadding: Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val sortedTopics = remember(topics) {
        topics.sortedWith(compareByDescending<TopicModel> { it.isPinned }.thenByDescending { it.order })
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp,
            top = topOverlayPadding + 8.dp,
            end = 12.dp,
            bottom = 8.dp
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemsIndexed(sortedTopics, key = { _, topic -> topic.id }) { _, topic ->
            TopicItem(topic = topic, onClick = { onTopicClick(topic) })
        }
    }
}

@Composable
fun TopicItem(
    topic: TopicModel,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        Color(topic.iconColor or 0xFF000000.toInt()).copy(alpha = 0.2f),
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (topic.iconCustomEmojiPath != null) {
                    StickerImage(
                        path = topic.iconCustomEmojiPath,
                        modifier = Modifier.size(28.dp),
                        animate = true
                    )
                } else {
                    Text(
                        text = topic.name.take(1).uppercase(),
                        color = Color(topic.iconColor or 0xFF000000.toInt()),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = topic.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (topic.isClosed) {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = "Closed",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    if (topic.lastMessageSenderAvatar != null || topic.lastMessageSenderName != null) {
                        Avatar(
                            path = topic.lastMessageSenderAvatar,
                            name = topic.lastMessageSenderName ?: "",
                            size = 18.dp,
                            fontSize = 8
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    Text(
                        text = if (topic.isClosed && topic.lastMessageText.isEmpty()) "Closed" else topic.lastMessageText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (topic.lastMessageTime.isNotEmpty()) {
                    Text(
                        text = topic.lastMessageTime,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (topic.isPinned) {
                        Icon(
                            Icons.Rounded.PushPin,
                            contentDescription = "Pinned",
                            modifier = Modifier
                                .size(14.dp)
                                .rotate(45f),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                        if (topic.unreadCount > 0) Spacer(modifier = Modifier.width(6.dp))
                    }
                    if (topic.unreadCount > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        ) {
                            Text(
                                text = topic.unreadCount.toString(),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

