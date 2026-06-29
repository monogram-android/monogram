package org.monogram.presentation.features.chats.conversation.ui.content

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import org.monogram.presentation.features.chats.conversation.ChatComponent
import org.monogram.presentation.features.chats.conversation.ChatConversationLog
import org.monogram.presentation.features.chats.conversation.ChatScrollCommand
import org.monogram.presentation.features.chats.conversation.ChatViewportPhase
import org.monogram.presentation.features.chats.conversation.DefaultChatComponent
import org.monogram.presentation.features.chats.conversation.logic.requestMessageHighlight
import kotlin.math.abs

internal fun shouldAutoFollowLatestAfterContentChange(
    previousLastGroupedMessageId: Long?,
    currentLastGroupedMessageId: Long?,
    followLatestArmed: Boolean,
    viewportPhase: ChatViewportPhase,
    pendingScrollCommand: ChatScrollCommand?,
    isLoading: Boolean,
    isLoadingOlder: Boolean,
    isLoadingNewer: Boolean,
    isScrollInProgress: Boolean,
    showInitialLoading: Boolean,
    isLatestLoaded: Boolean
): Boolean {
    if (!followLatestArmed) return false
    if (viewportPhase != ChatViewportPhase.Settled) return false
    if (previousLastGroupedMessageId == null || currentLastGroupedMessageId == null) return false
    if (previousLastGroupedMessageId == currentLastGroupedMessageId) return false
    if (pendingScrollCommand != null) return false
    if (isLoading || isLoadingOlder || isLoadingNewer || isScrollInProgress || showInitialLoading) return false
    if (!isLatestLoaded) return false
    return true
}

internal fun updateFollowLatestArmed(
    previousArmed: Boolean,
    isNearBottom: Boolean,
    hasUserScrolledAwayFromBottom: Boolean,
    isDragged: Boolean
): Boolean {
    if (isNearBottom) return true
    if (isDragged || hasUserScrolledAwayFromBottom) return false
    return previousArmed
}

internal fun shouldDisarmFollowLatest(
    pendingScrollCommand: ChatScrollCommand?
): Boolean {
    return when (pendingScrollCommand) {
        is ChatScrollCommand.JumpToMessage,
        is ChatScrollCommand.ScrollToStart -> true

        is ChatScrollCommand.RestoreViewport -> !pendingScrollCommand.atBottom
        null,
        is ChatScrollCommand.ScrollToBottom -> false
    }
}

internal fun shouldRetainBottomAlignmentAfterContentChange(
    viewportPhase: ChatViewportPhase,
    pendingScrollCommand: ChatScrollCommand?,
    stateIsAtBottom: Boolean,
    measuredIsAtBottom: Boolean,
    isLoading: Boolean,
    isLoadingOlder: Boolean,
    isLoadingNewer: Boolean,
    isScrollInProgress: Boolean,
    bottomAlignmentDeltaPx: Float?,
    alignmentTolerancePx: Float = 1f
): Boolean {
    if (viewportPhase != ChatViewportPhase.Settled) return false
    if (pendingScrollCommand != null) return false
    if (!stateIsAtBottom && !measuredIsAtBottom) return false
    if (isLoading || isLoadingOlder || isLoadingNewer || isScrollInProgress) return false
    val delta = bottomAlignmentDeltaPx ?: return false
    return abs(delta) > alignmentTolerancePx
}

internal fun shouldAutoSettleViewportAfterContentReady(
    viewportPhase: ChatViewportPhase,
    pendingScrollCommand: ChatScrollCommand?,
    hasMessages: Boolean,
    viewAsTopics: Boolean,
    currentTopicId: Long?,
    topicsCount: Int
): Boolean {
    if (viewportPhase == ChatViewportPhase.Settled) return false
    if (pendingScrollCommand != null) return false
    return hasMessages || (viewAsTopics && currentTopicId == null && topicsCount > 0)
}

@Composable
internal fun ChatContentEffects(
    component: ChatComponent,
    state: ChatComponent.State,
    scrollState: LazyListState,
    groupedMessages: List<GroupedMessageItem>,
    groupedMessageIndexById: Map<Long, Int>,
    isComments: Boolean,
    isForumList: Boolean,
    effectsEnabled: Boolean,
    componentInstanceId: String?,
    uiInstanceId: String,
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
    val isViewportSettled = effectsEnabled && state.viewportPhase == ChatViewportPhase.Settled
    val firstGroupedMessageId = groupedMessages.firstOrNull()?.firstMessageId
    val lastGroupedMessageId = groupedMessages.lastOrNull()?.firstMessageId
    val conversationItems = remember(
        groupedMessages,
        state.channelSponsoredMessages,
        isComments
    ) {
        if (isComments) {
            groupedMessages.mapIndexed { index, item ->
                ConversationListItem.Grouped(
                    groupedIndex = index,
                    groupedMessageItem = item
                )
            }
        } else {
            buildConversationListItems(
                groupedMessages = groupedMessages,
                sponsoredFeed = state.channelSponsoredMessages?.takeIf { it.messages.isNotEmpty() }
            )
        }
    }
    var followLatestArmed by remember { mutableStateOf(false) }
    var previousLastGroupedMessageId by remember { mutableStateOf<Long?>(null) }
    suspend fun consumeScrollCommandAndSettle() {
        ChatConversationLog.logState(
            stream = ChatConversationLog.STREAM_VIEWPORT,
            event = "effects_consume_scroll_command",
            state = latestUiState.value,
            componentInstanceId = componentInstanceId,
            uiInstanceId = uiInstanceId,
            extra = "pending=${latestUiState.value.pendingScrollCommand?.javaClass?.simpleName ?: "none"}"
        )
        component.onScrollCommandConsumed()
        withFrameNanos { }
        ChatConversationLog.logState(
            stream = ChatConversationLog.STREAM_VIEWPORT,
            event = "effects_settle_after_scroll_command",
            state = latestUiState.value,
            componentInstanceId = componentInstanceId,
            uiInstanceId = uiInstanceId
        )
        component.onViewportSettled()
    }

    LaunchedEffect(effectsEnabled, state.viewportPhase) {
        if (!isViewportSettled) return@LaunchedEffect
        ChatConversationLog.logState(
            stream = ChatConversationLog.STREAM_VIEWPORT,
            event = "effects_viewport_settled_visible",
            state = state,
            componentInstanceId = componentInstanceId,
            uiInstanceId = uiInstanceId
        )
        onVisible()
        if (state.fullScreenVideoPath != null || state.fullScreenVideoMessageId != null) {
            component.onDismissVideo()
        }
    }

    LaunchedEffect(
        effectsEnabled,
        state.viewportPhase,
        state.pendingScrollCommand,
        state.messages.size,
        state.topics.size,
        state.viewAsTopics,
        state.currentTopicId
    ) {
        if (!effectsEnabled) return@LaunchedEffect
        val shouldAutoSettle = shouldAutoSettleViewportAfterContentReady(
            viewportPhase = state.viewportPhase,
            pendingScrollCommand = state.pendingScrollCommand,
            hasMessages = state.messages.isNotEmpty(),
            viewAsTopics = state.viewAsTopics,
            currentTopicId = state.currentTopicId,
            topicsCount = state.topics.size
        )
        ChatConversationLog.logState(
            stream = ChatConversationLog.STREAM_VIEWPORT,
            event = "effects_auto_settle_check",
            state = state,
            componentInstanceId = componentInstanceId,
            uiInstanceId = uiInstanceId,
            extra = "effectsEnabled=$effectsEnabled shouldAutoSettle=$shouldAutoSettle"
        )
        if (!shouldAutoSettle) return@LaunchedEffect
        withFrameNanos { }
        ChatConversationLog.logState(
            stream = ChatConversationLog.STREAM_VIEWPORT,
            event = "effects_auto_settle_fire",
            state = state,
            componentInstanceId = componentInstanceId,
            uiInstanceId = uiInstanceId
        )
        component.onViewportSettled()
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
        effectsEnabled,
        state.isLoading,
        state.messages.isEmpty(),
        state.viewAsTopics,
        state.currentTopicId,
        state.isLoadingTopics,
        state.rootMessage
    ) {
        if (!effectsEnabled) return@LaunchedEffect
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
        effectsEnabled,
        state.pendingScrollCommand,
        isComments,
        groupedMessages.size,
        firstGroupedMessageId,
        lastGroupedMessageId,
        conversationItems
    ) {
        if (!effectsEnabled) return@LaunchedEffect
        val command = state.pendingScrollCommand ?: return@LaunchedEffect

        val leadingItems = chatContentLeadingItemsCount(
            isComments = isComments,
            showNavPadding = false,
            isLoadingOlder = state.isLoadingOlder,
            isLoadingNewer = state.isLoadingNewer,
            isAtBottom = state.isAtBottom,
            hasMessages = groupedMessages.isNotEmpty()
        )
        val groupedLazyIndexByFirstMessageId = buildGroupedLazyIndexByFirstMessageId(
            conversationItems = conversationItems,
            leadingItemsCount = leadingItems
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
                        val targetIndex = groupedLazyIndexByFirstMessageId[
                            groupedMessages[groupedIndex].firstMessageId
                        ] ?: groupedIndexToLazyIndex(groupedIndex, leadingItems)
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
                consumeScrollCommandAndSettle()
            }

            is ChatScrollCommand.JumpToMessage -> {
                val groupedIndex = groupedMessageIndexById[command.messageId]
                    ?: awaitGroupedIndex(
                        messageId = command.messageId,
                        groupedMessageIndexByIdProvider = { groupedMessageIndexById }
                    )
                    ?: -1
                if (groupedIndex >= 0) {
                    val targetIndex = groupedLazyIndexByFirstMessageId[
                        groupedMessages[groupedIndex].firstMessageId
                    ] ?: groupedIndexToLazyIndex(groupedIndex, leadingItems)
                    scrollState.scrollToMessageIndex(
                        index = targetIndex,
                        align = command.align,
                        animated = command.animated && state.isChatAnimationsEnabled,
                        staged = true
                    )
                    if (command.highlight) {
                        (component as? DefaultChatComponent)?.requestMessageHighlight(command.messageId)
                    }
                }
                consumeScrollCommandAndSettle()
            }

            is ChatScrollCommand.ScrollToBottom -> {
                scrollState.scrollToChatBottomStaged(
                    isComments = isComments,
                    animated = command.animated && state.isChatAnimationsEnabled
                )
                consumeScrollCommandAndSettle()
            }

            is ChatScrollCommand.ScrollToStart -> {
                scrollState.scrollToChatStartStaged(
                    animated = command.animated && state.isChatAnimationsEnabled
                )
                consumeScrollCommandAndSettle()
            }
        }
    }

    LaunchedEffect(effectsEnabled, state.viewportPhase, state.pendingScrollCommand) {
        if (!effectsEnabled) return@LaunchedEffect
        if (shouldDisarmFollowLatest(state.pendingScrollCommand)) {
            followLatestArmed = false
        }
    }

    LaunchedEffect(
        effectsEnabled,
        state.viewportPhase,
        scrollState,
        isComments,
        isForumList,
        showInitialLoading,
        isDragged,
        hasUserScrolledAwayFromBottom
    ) {
        if (!isViewportSettled) return@LaunchedEffect
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

                val nextHasUserScrolledAwayFromBottom = when {
                    snapshot.isNearBottom -> false
                    isDragged -> true
                    else -> hasUserScrolledAwayFromBottom
                }
                if (snapshot.isNearBottom) {
                    onHasUserScrolledAwayFromBottomChanged(false)
                } else if (isDragged) {
                    onHasUserScrolledAwayFromBottomChanged(true)
                }
                followLatestArmed = updateFollowLatestArmed(
                    previousArmed = followLatestArmed,
                    isNearBottom = snapshot.isNearBottom,
                    hasUserScrolledAwayFromBottom = nextHasUserScrolledAwayFromBottom,
                    isDragged = isDragged
                )

                val shouldShow = !isForumList &&
                        !showInitialLoading &&
                        (snapshot.unreadCount > 0 ||
                                (nextHasUserScrolledAwayFromBottom && !snapshot.isNearBottom))

                if (shouldShow) {
                    onShowScrollToBottomButtonChanged(true)
                } else {
                    delay(120)
                    val keepVisible = snapshot.unreadCount > 0 ||
                            (nextHasUserScrolledAwayFromBottom && !snapshot.isNearBottom)
                    if (!keepVisible) {
                        onShowScrollToBottomButtonChanged(false)
                    }
                }
            }
    }

    LaunchedEffect(
        effectsEnabled,
        state.viewportPhase,
        lastGroupedMessageId,
        state.pendingScrollCommand,
        state.isLoading,
        state.isLoadingOlder,
        state.isLoadingNewer,
        state.isLatestLoaded,
        showInitialLoading,
        isDragged
    ) {
        if (!effectsEnabled) return@LaunchedEffect
        val shouldAutoFollow = shouldAutoFollowLatestAfterContentChange(
            previousLastGroupedMessageId = previousLastGroupedMessageId,
            currentLastGroupedMessageId = lastGroupedMessageId,
            followLatestArmed = followLatestArmed,
            viewportPhase = state.viewportPhase,
            pendingScrollCommand = state.pendingScrollCommand,
            isLoading = state.isLoading,
            isLoadingOlder = state.isLoadingOlder,
            isLoadingNewer = state.isLoadingNewer,
            isScrollInProgress = isDragged || scrollState.isScrollInProgress,
            showInitialLoading = showInitialLoading,
            isLatestLoaded = state.isLatestLoaded
        )
        if (shouldAutoFollow) {
            scrollState.scrollToChatBottomStaged(
                isComments = isComments,
                animated = state.isChatAnimationsEnabled
            )
        }
        previousLastGroupedMessageId = lastGroupedMessageId
    }

    LaunchedEffect(
        effectsEnabled,
        state.viewportPhase,
        scrollState,
        groupedMessages.size,
        firstGroupedMessageId,
        lastGroupedMessageId,
        conversationItems,
        isComments,
        state.isLatestLoaded,
        state.isLoadingOlder,
        state.isLoadingNewer,
        state.isAtBottom
    ) {
        if (!isViewportSettled) return@LaunchedEffect
        snapshotFlow {
            buildViewportSnapshot(
                scrollState = scrollState,
                groupedMessages = groupedMessages,
                conversationItems = conversationItems,
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
        effectsEnabled,
        state.viewportPhase,
        scrollState,
        groupedMessages.size,
        firstGroupedMessageId,
        lastGroupedMessageId,
        conversationItems,
        isComments,
        state.currentTopicId,
        state.isLatestLoaded,
        state.isLoadingOlder,
        state.isLoadingNewer,
        state.isAtBottom
    ) {
        onDispose {
            if (!isViewportSettled) return@onDispose
            val viewport = buildViewportSnapshot(
                scrollState = scrollState,
                groupedMessages = groupedMessages,
                conversationItems = conversationItems,
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

    LaunchedEffect(
        effectsEnabled,
        state.viewportPhase,
        scrollState,
        groupedMessages.size,
        firstGroupedMessageId,
        lastGroupedMessageId,
        conversationItems
    ) {
        if (!isViewportSettled) return@LaunchedEffect
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
                        val grouped = when (
                            val conversationItem = conversationItems.getOrNull(
                                lazyIndexToGroupedIndex(item.index, leadingItemsCount)
                            )
                        ) {
                            is ConversationListItem.Grouped -> conversationItem.groupedMessageItem
                            else -> null
                        }
                        grouped?.let { grouped ->
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
                        val grouped = when (
                            val conversationItem = conversationItems.getOrNull(
                                lazyIndexToGroupedIndex(index, leadingItemsCount)
                            )
                        ) {
                            is ConversationListItem.Grouped -> conversationItem.groupedMessageItem
                            else -> null
                        }
                        grouped?.let { grouped ->
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

    LaunchedEffect(
        effectsEnabled,
        state.viewportPhase,
        state.pendingScrollCommand,
        groupedMessages.size,
        state.isLatestLoaded
    ) {
        if (!isViewportSettled) return@LaunchedEffect
        if (isComments) return@LaunchedEffect

        val isAtBottomNow = scrollState.isAtBottom(
            isComments = isComments,
            isLatestLoaded = state.isLatestLoaded
        )
        val bottomAlignmentDelta = scrollState.bottomAlignmentDelta(isComments = isComments)
        if (shouldRetainBottomAlignmentAfterContentChange(
                viewportPhase = state.viewportPhase,
                pendingScrollCommand = state.pendingScrollCommand,
                stateIsAtBottom = state.isAtBottom,
                measuredIsAtBottom = isAtBottomNow,
                isLoading = state.isLoading,
                isLoadingOlder = state.isLoadingOlder,
                isLoadingNewer = state.isLoadingNewer,
                isScrollInProgress = scrollState.isScrollInProgress,
                bottomAlignmentDeltaPx = bottomAlignmentDelta
            )
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

