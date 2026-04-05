package org.monogram.presentation.features.chats.currentChat.chatContent

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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.TopicModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.currentChat.ChatComponent
import org.monogram.presentation.features.chats.currentChat.components.*
import org.monogram.presentation.features.chats.currentChat.components.channels.ChannelMessageBubbleContainer
import org.monogram.presentation.features.stickers.ui.view.StickerImage
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatContentList(
    state: ChatComponent.State,
    component: ChatComponent,
    scrollState: LazyListState,
    groupedMessages: List<GroupedMessageItem>,
    onPhotoClick: (MessageModel, List<String>, List<String?>, List<Long>, Int) -> Unit,
    onPhotoDownload: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showNavPadding: Boolean = false,
    onVideoClick: (MessageModel, String?, String?) -> Unit,
    onDocumentClick: (MessageModel) -> Unit,
    onAudioClick: (MessageModel) -> Unit,
    onMessageOptionsClick: (MessageModel, Offset, IntSize, Offset) -> Unit,
    onGoToReply: (MessageModel) -> Unit,
    selectedMessageId: Long? = null,
    onMessagePositionChange: (Offset, IntSize) -> Unit = { _, _ -> },
    onViaBotClick: (String) -> Unit = {},
    toProfile: (Long) -> Unit,
    downloadUtils: IDownloadUtils,
    isAnyViewerOpen: Boolean = false
) {
    val isComments = state.rootMessage != null
    val isScrolling by remember(scrollState) { derivedStateOf { scrollState.isScrollInProgress } }

    LaunchedEffect(
        scrollState,
        groupedMessages.size,
        state.isLoading,
        state.isLoadingOlder,
        state.isLoadingNewer,
        state.isLatestLoaded,
        state.isOldestLoaded,
        state.isAtBottom,
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
                if (state.isLoading || state.isLoadingOlder || state.isLoadingNewer) return@collect

                val nearStart = firstVisibleIndex <= 2
                val nearEnd = lastVisibleIndex >= (groupedMessages.size - 3).coerceAtLeast(0)

                if (isComments) {
                    if (!scrollState.isScrollInProgress) return@collect

                    if (nearStart && !state.isOldestLoaded) {
                        component.loadMore()
                    } else if (nearEnd && !state.isLatestLoaded) {
                        component.loadNewer()
                    }
                } else {
                    if (nearEnd && !state.isOldestLoaded) {
                        component.loadMore()
                    } else if (nearStart && !state.isAtBottom && !state.isLatestLoaded) {
                        component.loadNewer()
                    }
                }
            }
    }

    if (state.viewAsTopics && state.currentTopicId == null) {
        TopicsList(
            topics = state.topics,
            onTopicClick = { component.onTopicClick(it.id) },
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
        contentPadding = PaddingValues(vertical = 8.dp)
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
            if (state.rootMessage != null) {
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
                        downloadUtils,
                        isAnyViewerOpen = isAnyViewerOpen
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
                    isScrolling = isScrolling,
                    downloadUtils = downloadUtils,
                    isAnyViewerOpen = isAnyViewerOpen
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
            if (state.rootMessage != null) {
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
                        downloadUtils,
                        isAnyViewerOpen = isAnyViewerOpen
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
                    isScrolling = isScrolling,
                    downloadUtils = downloadUtils,
                    isAnyViewerOpen = isAnyViewerOpen
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
    state: ChatComponent.State,
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
    isScrolling: Boolean,
    downloadUtils: IDownloadUtils,
    isAnyViewerOpen: Boolean = false
) {
    val mainMsg = remember(item) {
        if (item is GroupedMessageItem.Single) item.message else (item as GroupedMessageItem.Album).messages.last()
    }

    val shouldAnimateEntry = state.isChatAnimationsEnabled && !isScrolling

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
        } else if (!shouldAnimateEntry) {
            scale.snapTo(1f)
            itemAlpha.snapTo(1f)
            offsetY.snapTo(0f)
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
    state: ChatComponent.State,
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
    downloadUtils: IDownloadUtils,
    isAnyViewerOpen: Boolean = false
) {
    val isChannel = state.isChannel && state.currentTopicId == null

    when (item) {
        is GroupedMessageItem.Single -> {
            if (item.message.content is MessageContent.Service) {
                ServiceMessage(text = (item.message.content as MessageContent.Service).text)
            } else if (isChannel) {
                ChannelMessageBubbleContainer(
                    msg = item.message,
                    olderMsg = olderMsg,
                    newerMsg = newerMsg,
                    autoplayGifs = state.autoplayGifs,
                    autoplayVideos = state.autoplayVideos,
                    autoDownloadFiles = state.autoDownloadFiles,
                    highlighted = state.highlightedMessageId == item.message.id,
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
                        if (isSelectionMode) component.onToggleMessageSelection(item.message.id) else onMessageOptionsClick(
                            item.message,
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
                            item.message.senderId
                        )
                    },
                    onStickerClick = {
                        if (isSelectionMode) component.onToggleMessageSelection(item.message.id) else component.onStickerClick(
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
                    shouldReportPosition = item.message.id == selectedMessageId,
                    onPositionChange = { _, pos, size -> onMessagePositionChange(pos, size) },
                    onCommentsClick = { component.onCommentsClick(it) },
                    toProfile = toProfile,
                    onViaBotClick = onViaBotClick,
                    onYouTubeClick = { component.onOpenYouTube(it) },
                    onInstantViewClick = { component.onOpenInstantView(it) },
                    downloadUtils = downloadUtils,
                    isAnyViewerOpen = isAnyViewerOpen
                )
            } else {
                MessageBubbleContainer(
                    msg = item.message,
                    olderMsg = olderMsg,
                    newerMsg = newerMsg,
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
                    highlighted = state.highlightedMessageId == item.message.id,
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
                        if (isSelectionMode) component.onToggleMessageSelection(item.message.id) else onMessageOptionsClick(
                            item.message,
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
                            item.message.senderId
                        )
                    },
                    onStickerClick = {
                        if (isSelectionMode) component.onToggleMessageSelection(item.message.id) else component.onStickerClick(
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
                    shouldReportPosition = item.message.id == selectedMessageId,
                    onPositionChange = { _, pos, size -> onMessagePositionChange(pos, size) },
                    toProfile = toProfile,
                    onViaBotClick = onViaBotClick,
                    canReply = state.canWrite && !isSelectionMode,
                    onReplySwipe = { component.onReplyMessage(it) },
                    swipeEnabled = !isSelectionMode,
                    downloadUtils = downloadUtils,
                    isAnyViewerOpen = isAnyViewerOpen
                )
            }
        }

        is GroupedMessageItem.Album -> {
            AlbumMessageBubbleContainer(
                messages = item.messages,
                olderMsg = olderMsg,
                newerMsg = newerMsg,
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
                        item.messages,
                        onPhotoClick
                    )
                },
                onDownloadPhoto = onPhotoDownload,
                onVideoClick = {
                    if (isSelectionMode) component.onToggleMessageSelection(it.id) else handleAlbumVideoClick(
                        it,
                        item.messages,
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
                    if (isSelectionMode) component.onToggleMessageSelection(item.messages.last().id) else onMessageOptionsClick(
                        item.messages.last(),
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
                shouldReportPosition = item.messages.last().id == selectedMessageId,
                onPositionChange = { _, pos, size -> onMessagePositionChange(pos, size) },
                onCommentsClick = { component.onCommentsClick(it) },
                toProfile = toProfile,
                onViaBotClick = onViaBotClick,
                canReply = state.canWrite && !isSelectionMode,
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
    state: ChatComponent.State,
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
                toProfile = toProfile, swipeEnabled = false,
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
    modifier: Modifier = Modifier
) {
    val sortedTopics = remember(topics) {
        topics.sortedWith(compareByDescending<TopicModel> { it.isPinned }.thenByDescending { it.order })
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
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

