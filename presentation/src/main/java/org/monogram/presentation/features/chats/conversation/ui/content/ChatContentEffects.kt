package org.monogram.presentation.features.chats.conversation.ui.content

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import org.monogram.presentation.features.chats.conversation.ChatComponent
import org.monogram.presentation.features.chats.conversation.ChatScrollCommand
import org.monogram.presentation.features.chats.conversation.DefaultChatComponent

@Composable
internal fun ChatContentEffects(
    component: ChatComponent,
    state: ChatComponent.State,
    scrollState: LazyListState,
    groupedMessages: List<GroupedMessageItem>,
    groupedMessageIndexById: Map<Long, Int>,
    isComments: Boolean,
    isForumList: Boolean,
    isDragged: Boolean,
    isRecordingVideo: Boolean,
    showInitialLoading: Boolean,
    hasUserScrolledAwayFromBottom: Boolean,
    transformedMessageTexts: MutableMap<Long, String>,
    originalMessageTexts: MutableMap<Long, String>,
    onVisible: () -> Unit,
    onShowInitialLoadingChanged: (Boolean) -> Unit,
    onHasUserScrolledAwayFromBottomChanged: (Boolean) -> Unit,
    onShowScrollToBottomButtonChanged: (Boolean) -> Unit,
    onHideKeyboardAndClearFocus: (Boolean) -> Unit,
    onRenderPinnedMessagesListChanged: (Boolean) -> Unit,
    onSearchFiltersChanged: (Boolean) -> Unit,
    onSearchSenderPickerChanged: (Boolean) -> Unit
) {
    val latestUiState = rememberUpdatedState(state)
    val firstGroupedMessageId = groupedMessages.firstOrNull()?.firstMessageId
    val lastGroupedMessageId = groupedMessages.lastOrNull()?.firstMessageId

    LaunchedEffect(Unit) {
        onVisible()
        if (state.fullScreenVideoPath != null || state.fullScreenVideoMessageId != null) {
            component.onDismissVideo()
        }
    }

    LaunchedEffect(state.messages) {
        if (transformedMessageTexts.isEmpty() && originalMessageTexts.isEmpty()) return@LaunchedEffect
        val ids = state.messages.map { it.id }.toSet()
        transformedMessageTexts.keys.toList().forEach { id ->
            if (id !in ids) {
                transformedMessageTexts.remove(id)
                originalMessageTexts.remove(id)
            }
        }
    }

    LaunchedEffect(
        state.isLoading,
        state.messages.isEmpty(),
        state.viewAsTopics,
        state.currentTopicId,
        state.isLoadingTopics,
        state.rootMessage
    ) {
        val isActuallyLoading = if (state.viewAsTopics && state.currentTopicId == null) {
            state.isLoadingTopics && state.topics.isEmpty()
        } else if (state.currentTopicId != null) {
            state.isLoading && state.messages.isEmpty() && state.rootMessage == null
        } else {
            state.isLoading && state.messages.isEmpty()
        }
        if (isActuallyLoading) {
            if (state.isChatAnimationsEnabled) delay(200)
            onShowInitialLoadingChanged(true)
        } else {
            onShowInitialLoadingChanged(false)
        }
    }

    LaunchedEffect(
        state.pendingScrollCommand,
        isComments,
        groupedMessages.size,
        firstGroupedMessageId,
        lastGroupedMessageId
    ) {
        val command = state.pendingScrollCommand ?: return@LaunchedEffect

        val leadingItems = chatContentLeadingItemsCount(
            isComments = isComments,
            showNavPadding = false,
            isLoadingOlder = state.isLoadingOlder,
            isLoadingNewer = state.isLoadingNewer,
            isAtBottom = state.isAtBottom,
            hasMessages = groupedMessages.isNotEmpty()
        )

        when (command) {
            is ChatScrollCommand.RestoreViewport -> {
                if (command.atBottom || command.anchorMessageId == null) {
                    scrollState.scrollToChatBottomStaged(
                        isComments = isComments,
                        animated = false
                    )
                } else {
                    val groupedIndex = groupedMessageIndexById[command.anchorMessageId]
                        ?: awaitGroupedIndex(
                            messageId = command.anchorMessageId,
                            groupedMessageIndexByIdProvider = { groupedMessageIndexById }
                        )
                        ?: -1
                    if (groupedIndex >= 0) {
                        val targetIndex = groupedIndexToLazyIndex(groupedIndex, leadingItems)
                        scrollState.restoreViewportAtIndex(
                            targetIndex = targetIndex,
                            anchorOffsetPx = command.anchorOffsetPx
                        )
                    } else {
                        scrollState.scrollToChatBottomStaged(
                            isComments = isComments,
                            animated = false
                        )
                    }
                }
                component.onScrollCommandConsumed()
            }

            is ChatScrollCommand.JumpToMessage -> {
                val groupedIndex = groupedMessageIndexById[command.messageId]
                    ?: awaitGroupedIndex(
                        messageId = command.messageId,
                        groupedMessageIndexByIdProvider = { groupedMessageIndexById }
                    )
                    ?: -1
                if (groupedIndex >= 0) {
                    val targetIndex = groupedIndexToLazyIndex(groupedIndex, leadingItems)
                    scrollState.scrollToMessageIndex(
                        index = targetIndex,
                        align = command.align,
                        animated = command.animated && state.isChatAnimationsEnabled,
                        staged = true
                    )
                }
                component.onScrollCommandConsumed()
            }

            is ChatScrollCommand.ScrollToBottom -> {
                scrollState.scrollToChatBottomStaged(
                    isComments = isComments,
                    animated = command.animated && state.isChatAnimationsEnabled
                )
                component.onScrollCommandConsumed()
            }

            is ChatScrollCommand.ScrollToStart -> {
                scrollState.scrollToChatStartStaged(
                    animated = command.animated && state.isChatAnimationsEnabled
                )
                component.onScrollCommandConsumed()
            }
        }
    }

    LaunchedEffect(
        scrollState,
        isComments,
        isForumList,
        showInitialLoading,
        isDragged,
        hasUserScrolledAwayFromBottom
    ) {
        var lastReportedBottomState: Boolean? = null
        snapshotFlow {
            val currentState = latestUiState.value
            BottomVisibilitySnapshot(
                isAtBottom = scrollState.isAtBottom(
                    isComments = isComments,
                    isLatestLoaded = currentState.isLatestLoaded
                ),
                isNearBottom = scrollState.isNearBottom(isComments = isComments),
                unreadCount = currentState.unreadCount
            )
        }
            .distinctUntilChanged()
            .collectLatest { snapshot ->
                if (lastReportedBottomState != snapshot.isAtBottom) {
                    component.onBottomReached(snapshot.isAtBottom)
                    lastReportedBottomState = snapshot.isAtBottom
                }

                if (snapshot.isNearBottom) {
                    onHasUserScrolledAwayFromBottomChanged(false)
                } else if (isDragged) {
                    onHasUserScrolledAwayFromBottomChanged(true)
                }

                val shouldShow = !isForumList &&
                        !showInitialLoading &&
                        (snapshot.unreadCount > 0 || (hasUserScrolledAwayFromBottom && !snapshot.isNearBottom))

                if (shouldShow) {
                    onShowScrollToBottomButtonChanged(true)
                } else {
                    delay(120)
                    val keepVisible = snapshot.unreadCount > 0 ||
                            (hasUserScrolledAwayFromBottom && !snapshot.isNearBottom)
                    if (!keepVisible) {
                        onShowScrollToBottomButtonChanged(false)
                    }
                }
            }
    }

    LaunchedEffect(
        scrollState,
        groupedMessages.size,
        firstGroupedMessageId,
        lastGroupedMessageId,
        isComments,
        state.isLatestLoaded,
        state.isLoadingOlder,
        state.isLoadingNewer,
        state.isAtBottom
    ) {
        snapshotFlow {
            buildViewportSnapshot(
                scrollState = scrollState,
                groupedMessages = groupedMessages,
                isComments = isComments,
                isLatestLoaded = state.isLatestLoaded,
                isLoadingOlder = state.isLoadingOlder,
                isLoadingNewer = state.isLoadingNewer,
                isAtBottom = state.isAtBottom,
                showNavPadding = false
            )
        }
            .filterNotNull()
            .distinctUntilChanged()
            .debounce(120)
            .collect { viewport ->
                component.updateViewport(viewport)
            }
    }

    DisposableEffect(
        scrollState,
        groupedMessages.size,
        firstGroupedMessageId,
        lastGroupedMessageId,
        isComments,
        state.currentTopicId,
        state.isLatestLoaded,
        state.isLoadingOlder,
        state.isLoadingNewer,
        state.isAtBottom
    ) {
        onDispose {
            val viewport = buildViewportSnapshot(
                scrollState = scrollState,
                groupedMessages = groupedMessages,
                isComments = isComments,
                isLatestLoaded = state.isLatestLoaded,
                isLoadingOlder = state.isLoadingOlder,
                isLoadingNewer = state.isLoadingNewer,
                isAtBottom = state.isAtBottom,
                showNavPadding = false
            )
            if (viewport != null) {
                component.updateViewport(viewport)
            }
        }
    }

    LaunchedEffect(scrollState, groupedMessages.size, firstGroupedMessageId, lastGroupedMessageId) {
        snapshotFlow { scrollState.layoutInfo.visibleItemsInfo }
            .map { visibleItems ->
                val currentState = latestUiState.value
                val leadingItemsCount = chatContentLeadingItemsCount(
                    isComments = currentState.rootMessage != null,
                    showNavPadding = false,
                    isLoadingOlder = currentState.isLoadingOlder,
                    isLoadingNewer = currentState.isLoadingNewer,
                    isAtBottom = currentState.isAtBottom,
                    hasMessages = groupedMessages.isNotEmpty()
                )
                val visibleIds = LinkedHashSet<Long>()
                val nearbyIds = LinkedHashSet<Long>()
                if (visibleItems.isNotEmpty()) {
                    val minIndex = visibleItems.minOf { it.index }
                    val maxIndex = visibleItems.maxOf { it.index }

                    visibleItems.forEach { item ->
                        val groupedIndex = lazyIndexToGroupedIndex(item.index, leadingItemsCount)
                        groupedMessages.getOrNull(groupedIndex)?.let { grouped ->
                            when (grouped) {
                                is GroupedMessageItem.Single -> visibleIds.add(grouped.message.id)
                                is GroupedMessageItem.Album -> grouped.messages.forEach { message ->
                                    visibleIds.add(message.id)
                                }
                            }
                        }
                    }

                    val nearbyStart = (minIndex - 5).coerceAtLeast(0)
                    val nearbyEnd = maxIndex + 5
                    for (index in nearbyStart..nearbyEnd) {
                        if (index in minIndex..maxIndex) continue
                        val groupedIndex = lazyIndexToGroupedIndex(index, leadingItemsCount)
                        groupedMessages.getOrNull(groupedIndex)?.let { grouped ->
                            when (grouped) {
                                is GroupedMessageItem.Single -> nearbyIds.add(grouped.message.id)
                                is GroupedMessageItem.Album -> grouped.messages.forEach { message ->
                                    nearbyIds.add(message.id)
                                }
                            }
                        }
                    }
                }
                val visibleIdList = visibleIds.toList()
                visibleIdList to nearbyIds.filterNot(visibleIds::contains)
            }
            .distinctUntilChanged()
            .debounce(100)
            .collect { (visibleIds, nearbyIds) ->
                (component as? DefaultChatComponent)?.let {
                    it.repositoryMessage.updateVisibleRange(it.chatId, visibleIds, nearbyIds)
                }
            }
    }

    LaunchedEffect(groupedMessages.size, state.isLatestLoaded) {
        if (isComments) return@LaunchedEffect

        val isAtBottomNow = scrollState.isAtBottom(
            isComments = isComments,
            isLatestLoaded = state.isLatestLoaded
        )
        if ((state.isAtBottom || isAtBottomNow) &&
            !state.isLoading &&
            !state.isLoadingOlder &&
            !state.isLoadingNewer &&
            !scrollState.isScrollInProgress
        ) {
            scrollState.scrollToChatBottomStaged(
                isComments = isComments,
                animated = state.isChatAnimationsEnabled
            )
        }
    }

    LaunchedEffect(isDragged) {
        if (isDragged) {
            onHideKeyboardAndClearFocus(false)
        }
    }

    LaunchedEffect(state.showBotCommands, isRecordingVideo) {
        if (state.showBotCommands || isRecordingVideo) {
            onHideKeyboardAndClearFocus(true)
        }
    }

    LaunchedEffect(state.showPinnedMessagesList) {
        if (state.showPinnedMessagesList) {
            onRenderPinnedMessagesListChanged(true)
        }
    }

    LaunchedEffect(state.isSearchActive) {
        if (state.isSearchActive) {
            onSearchFiltersChanged(false)
            onSearchSenderPickerChanged(false)
            if (state.showPinnedMessagesList) {
                component.onDismissPinnedMessages()
            }
        }
    }
}

