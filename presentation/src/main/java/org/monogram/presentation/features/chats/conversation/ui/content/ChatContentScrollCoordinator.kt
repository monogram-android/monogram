package org.monogram.presentation.features.chats.conversation.ui.content

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.monogram.domain.models.ChatViewportCacheEntry
import org.monogram.presentation.features.chats.conversation.ScrollAlign
import kotlin.math.abs

private const val BOTTOM_ALIGNMENT_TOLERANCE_PX = 1f
private const val BOTTOM_FAST_PATH_MAX_DELTA_PX = 120f
private const val BOTTOM_STAGED_SCROLL_DISTANCE_THRESHOLD = 24

@Immutable
internal data class BottomVisibilitySnapshot(
    val isAtBottom: Boolean,
    val isNearBottom: Boolean,
    val unreadCount: Int
)

@Immutable
internal data class ScrollTargetLayoutInfo(
    val index: Int,
    val offset: Int,
    val size: Int
)

@Immutable
internal data class ScrollToMessagePlan(
    val coarseIndex: Int?,
    val shouldAnimateToIndex: Boolean
)

@Immutable
internal data class BottomCorrectionPlan(
    val targetIndex: Int,
    val coarseIndex: Int?,
    val shouldScrollToTarget: Boolean,
    val alignmentDelta: Float?
)

internal fun calculateChatBottomTargetIndex(
    totalItemsCount: Int,
    isComments: Boolean
): Int? {
    if (totalItemsCount <= 0) return null
    return if (isComments) totalItemsCount - 1 else 0
}

internal fun calculateBottomAlignmentDelta(
    viewportStart: Int,
    viewportEnd: Int,
    itemOffset: Int,
    itemSize: Int,
    isComments: Boolean
): Float {
    return if (isComments) {
        ((itemOffset + itemSize) - viewportEnd).toFloat()
    } else {
        (itemOffset - viewportStart).toFloat()
    }
}

internal fun shouldUseVisibleBottomFastPath(
    targetAlreadyVisible: Boolean,
    bottomAlignmentDelta: Float,
    maxDeltaPx: Float = BOTTOM_FAST_PATH_MAX_DELTA_PX
): Boolean {
    return targetAlreadyVisible && abs(bottomAlignmentDelta) <= maxDeltaPx
}

internal fun needsBottomAlignmentCorrection(
    bottomAlignmentDelta: Float,
    tolerancePx: Float = BOTTOM_ALIGNMENT_TOLERANCE_PX
): Boolean {
    return abs(bottomAlignmentDelta) > tolerancePx
}

internal fun shouldPersistBottomViewportIntent(
    atBottomNow: Boolean,
    nearBottomNow: Boolean,
    stateIsAtBottom: Boolean,
    isComments: Boolean
): Boolean {
    if (atBottomNow) return true
    return !isComments && stateIsAtBottom && nearBottomNow
}

internal fun buildBottomCoarseScrollIndex(
    currentFirstVisibleIndex: Int,
    targetIndex: Int,
    totalItemsCount: Int,
    isComments: Boolean
): Int? {
    if (totalItemsCount <= 0) return null

    val boundedTargetIndex = targetIndex.coerceIn(0, totalItemsCount - 1)
    val distance = abs(currentFirstVisibleIndex - boundedTargetIndex)
    if (distance <= BOTTOM_STAGED_SCROLL_DISTANCE_THRESHOLD) return null

    return if (isComments) {
        (boundedTargetIndex - 8).coerceAtLeast(0)
    } else {
        (boundedTargetIndex + 8).coerceAtMost(totalItemsCount - 1)
    }
}

internal fun buildBottomCorrectionPlan(
    currentFirstVisibleIndex: Int,
    targetIndex: Int,
    totalItemsCount: Int,
    isComments: Boolean,
    visibleTargetDelta: Float?
): BottomCorrectionPlan? {
    if (totalItemsCount <= 0) return null

    val boundedTargetIndex = targetIndex.coerceIn(0, totalItemsCount - 1)
    if (visibleTargetDelta != null) {
        return BottomCorrectionPlan(
            targetIndex = boundedTargetIndex,
            coarseIndex = null,
            shouldScrollToTarget = false,
            alignmentDelta = visibleTargetDelta.takeIf(::needsBottomAlignmentCorrection)
        )
    }

    return BottomCorrectionPlan(
        targetIndex = boundedTargetIndex,
        coarseIndex = buildBottomCoarseScrollIndex(
            currentFirstVisibleIndex = currentFirstVisibleIndex,
            targetIndex = boundedTargetIndex,
            totalItemsCount = totalItemsCount,
            isComments = isComments
        ),
        shouldScrollToTarget = true,
        alignmentDelta = null
    )
}

internal fun LazyListState.bottomAlignmentDelta(isComments: Boolean): Float? {
    val info = layoutInfo
    val targetIndex =
        calculateChatBottomTargetIndex(info.totalItemsCount, isComments) ?: return null
    val targetInfo = info.visibleItemsInfo.firstOrNull { it.index == targetIndex } ?: return null
    return calculateBottomAlignmentDelta(
        viewportStart = info.viewportStartOffset,
        viewportEnd = info.viewportEndOffset,
        itemOffset = targetInfo.offset,
        itemSize = targetInfo.size,
        isComments = isComments
    )
}

internal suspend fun LazyListState.scrollToMessageIndex(
    index: Int,
    align: ScrollAlign,
    animated: Boolean,
    staged: Boolean
) {
    val total = layoutInfo.totalItemsCount
    if (total <= 0) return

    val boundedIndex = index.coerceIn(0, total - 1)
    val initialTarget = layoutInfo.visibleItemsInfo.firstOrNull { it.index == boundedIndex }

    if (initialTarget != null) {
        alignVisibleMessage(
            itemInfo = ScrollTargetLayoutInfo(
                index = initialTarget.index,
                offset = initialTarget.offset,
                size = initialTarget.size
            ),
            align = align,
            animated = animated
        )
        return
    }

    val plan = buildScrollToMessagePlan(
        currentFirstVisibleIndex = firstVisibleItemIndex,
        targetIndex = boundedIndex,
        totalItemsCount = total,
        targetAlreadyVisible = false,
        staged = staged
    )

    plan.coarseIndex?.let { coarseIndex ->
        scrollToItem(coarseIndex)
    }

    if (plan.shouldAnimateToIndex && animated) {
        animateScrollToItem(boundedIndex)
    } else {
        scrollToItem(boundedIndex)
    }

    val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == boundedIndex } ?: return
    alignVisibleMessage(
        itemInfo = ScrollTargetLayoutInfo(
            index = itemInfo.index,
            offset = itemInfo.offset,
            size = itemInfo.size
        ),
        align = align,
        animated = animated
    )
}

internal fun LazyListState.isAtBottom(
    isComments: Boolean,
    isLatestLoaded: Boolean
): Boolean {
    if (!isLatestLoaded) return false

    val info = layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return true

    return if (isComments) {
        val lastVisible = visible.last()
        lastVisible.index >= info.totalItemsCount - 1 &&
                abs((info.viewportEndOffset - (lastVisible.offset + lastVisible.size)).toFloat()) <= 40f
    } else {
        val firstVisible = visible.first()
        firstVisible.index == 0 &&
                abs((firstVisible.offset - info.viewportStartOffset).toFloat()) <= 40f
    }
}

internal fun LazyListState.isNearBottom(isComments: Boolean): Boolean {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return true

    return if (isComments) {
        val lastVisible = visible.last()
        val distance =
            abs((info.viewportEndOffset - (lastVisible.offset + lastVisible.size)).toFloat())
        lastVisible.index >= info.totalItemsCount - 2 && distance <= 240f
    } else {
        val firstVisible = visible.first()
        val distance = abs((firstVisible.offset - info.viewportStartOffset).toFloat())
        firstVisible.index <= 1 && distance <= 240f
    }
}

private suspend fun LazyListState.alignVisibleMessage(
    itemInfo: ScrollTargetLayoutInfo,
    align: ScrollAlign,
    animated: Boolean
) {
    val delta = calculateAlignmentDelta(
        viewportStart = layoutInfo.viewportStartOffset,
        viewportEnd = layoutInfo.viewportEndOffset,
        itemOffset = itemInfo.offset,
        itemSize = itemInfo.size,
        align = align
    )

    if (abs(delta) <= 1f) return

    if (animated) {
        animateScrollBy(delta)
    } else {
        scrollBy(delta)
    }
}

internal suspend fun LazyListState.scrollToChatBottomStaged(
    isComments: Boolean,
    animated: Boolean,
    bottomTargetIndex: Int? = null
) {
    val total = layoutInfo.totalItemsCount
    if (total <= 0) return

    val targetIndex =
        (bottomTargetIndex ?: calculateChatBottomTargetIndex(total, isComments) ?: return)
        .coerceIn(0, total - 1)
    val visibleTargetInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
    val visibleDelta = visibleTargetInfo?.let {
        calculateBottomAlignmentDelta(
            viewportStart = layoutInfo.viewportStartOffset,
            viewportEnd = layoutInfo.viewportEndOffset,
            itemOffset = it.offset,
            itemSize = it.size,
            isComments = isComments
        )
    }
    val plan = buildBottomCorrectionPlan(
        currentFirstVisibleIndex = firstVisibleItemIndex,
        targetIndex = targetIndex,
        totalItemsCount = total,
        isComments = isComments,
        visibleTargetDelta = visibleDelta
    ) ?: return

    plan.coarseIndex?.let { coarseIndex ->
        scrollToItem(coarseIndex)
    }

    if (plan.shouldScrollToTarget) {
        if (animated) {
            animateScrollToItem(plan.targetIndex)
        } else {
            scrollToItem(plan.targetIndex)
        }
    } else {
        plan.alignmentDelta?.let { delta ->
            if (animated) animateScrollBy(delta) else scrollBy(delta)
        }
    }

    withFrameNanos { }
    bottomAlignmentDelta(isComments = isComments)
        ?.takeIf(::needsBottomAlignmentCorrection)
        ?.let { scrollBy(it) }
}

internal suspend fun LazyListState.scrollToChatStartStaged(
    animated: Boolean
) {
    val total = layoutInfo.totalItemsCount
    if (total <= 0) return

    if (animated) {
        animateScrollToItem(0)
    } else {
        scrollToItem(0)
    }

    val targetInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
    if (targetInfo != null) {
        val delta = (targetInfo.offset - layoutInfo.viewportStartOffset).toFloat()
        if (abs(delta) > 1f) {
            scrollBy(delta)
        }
    }

}

internal suspend fun awaitGroupedIndex(
    messageId: Long,
    groupedMessageIndexByIdProvider: () -> Map<Long, Int>,
    timeoutMs: Long = 1200L
): Int? {
    return withTimeoutOrNull(timeoutMs) {
        snapshotFlow { groupedMessageIndexByIdProvider()[messageId] }
            .filterNotNull()
            .first()
    }
}

internal suspend fun awaitGroupedIndex(
    messageIds: List<Long>,
    groupedMessageIndexByIdProvider: () -> Map<Long, Int>,
    timeoutMs: Long = 1200L
): Int? {
    if (messageIds.isEmpty()) return null
    return withTimeoutOrNull(timeoutMs) {
        snapshotFlow {
            val indexById = groupedMessageIndexByIdProvider()
            messageIds.firstNotNullOfOrNull(indexById::get)
        }
            .filterNotNull()
            .first()
    }
}

internal suspend fun LazyListState.restoreViewportAtIndex(
    targetIndex: Int,
    anchorOffsetPx: Int
) {
    val total = layoutInfo.totalItemsCount
    if (total <= 0) return
    val boundedIndex = targetIndex.coerceIn(0, total - 1)

    scrollToItem(boundedIndex)
    val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == boundedIndex } ?: return
    val viewportStart = layoutInfo.viewportStartOffset
    val desiredOffset = viewportStart + anchorOffsetPx
    val delta = (itemInfo.offset - desiredOffset).toFloat()

    if (abs(delta) > 1f) {
        scrollBy(delta)
    }
}

internal fun buildScrollToMessagePlan(
    currentFirstVisibleIndex: Int,
    targetIndex: Int,
    totalItemsCount: Int,
    targetAlreadyVisible: Boolean,
    staged: Boolean
): ScrollToMessagePlan {
    if (totalItemsCount <= 0) {
        return ScrollToMessagePlan(coarseIndex = null, shouldAnimateToIndex = false)
    }

    if (targetAlreadyVisible) {
        return ScrollToMessagePlan(coarseIndex = null, shouldAnimateToIndex = false)
    }

    val boundedTargetIndex = targetIndex.coerceIn(0, totalItemsCount - 1)
    val distance = abs(currentFirstVisibleIndex - boundedTargetIndex)
    val coarseIndex = if (staged && distance > 20) {
        when {
            boundedTargetIndex > currentFirstVisibleIndex -> (boundedTargetIndex - 10).coerceAtLeast(
                0
            )

            boundedTargetIndex < currentFirstVisibleIndex -> (boundedTargetIndex + 10).coerceAtMost(
                totalItemsCount - 1
            )

            else -> boundedTargetIndex
        }
    } else {
        null
    }

    return ScrollToMessagePlan(
        coarseIndex = coarseIndex,
        shouldAnimateToIndex = distance > 0
    )
}

internal fun calculateAlignmentDelta(
    viewportStart: Int,
    viewportEnd: Int,
    itemOffset: Int,
    itemSize: Int,
    align: ScrollAlign
): Float {
    val viewportCenter = (viewportStart + viewportEnd) / 2
    val targetPosition = when (align) {
        ScrollAlign.Start -> viewportStart
        ScrollAlign.Center -> viewportCenter - (itemSize / 2)
        ScrollAlign.End -> viewportEnd - itemSize
    }
    return (itemOffset - targetPosition).toFloat()
}

internal fun buildViewportSnapshot(
    scrollState: LazyListState,
    groupedMessages: List<GroupedMessageItem>,
    conversationItems: List<ConversationListItem>,
    isComments: Boolean,
    isLatestLoaded: Boolean,
    isOldestLoaded: Boolean,
    isLoadingOlder: Boolean,
    isLoadingNewer: Boolean,
    isAtBottom: Boolean,
    showNavPadding: Boolean
): ChatViewportCacheEntry? {
    if (groupedMessages.isEmpty()) {
        return ChatViewportCacheEntry(atBottom = true, readFully = true)
    }

    val atBottomNow = scrollState.isAtBottom(
        isComments = isComments,
        isLatestLoaded = isLatestLoaded
    )
    val nearBottomNow = scrollState.isNearBottom(isComments = isComments)
    if (
        shouldPersistBottomViewportIntent(
            atBottomNow = atBottomNow,
            nearBottomNow = nearBottomNow,
            stateIsAtBottom = isAtBottom,
            isComments = isComments
        )
    ) {
        return ChatViewportCacheEntry(
            atBottom = true,
            readFully = isLatestLoaded,
            topEndMessageId = groupedMessages.firstOrNull()?.firstMessageId?.takeIf { isOldestLoaded }
        )
    }

    val leadingItems = chatContentLeadingItemsCount(
        isComments = isComments,
        showNavPadding = showNavPadding,
        isLoadingOlder = isLoadingOlder,
        isLoadingNewer = isLoadingNewer,
        isAtBottom = isAtBottom,
        isNearBottom = nearBottomNow,
        hasMessages = groupedMessages.isNotEmpty()
    )
    val info = scrollState.layoutInfo
    val anchorItem = info.visibleItemsInfo.firstOrNull { itemInfo ->
        when (
            conversationItems.getOrNull(
                lazyIndexToGroupedIndex(itemInfo.index, leadingItems)
            )
        ) {
            is ConversationListItem.Grouped -> true
            else -> false
        }
    } ?: return null

    val anchorEntry = when (
        val item = conversationItems.getOrNull(
            lazyIndexToGroupedIndex(anchorItem.index, leadingItems)
        )
    ) {
        is ConversationListItem.Grouped -> item.groupedMessageItem
        else -> null
    } ?: return null

    val anchorMessageId = anchorEntry.firstMessageId
    val anchorAliasIds = when (anchorEntry) {
        is GroupedMessageItem.Single -> emptyList()
        is GroupedMessageItem.Album -> anchorEntry.messages.map { it.id }
            .filterNot { it == anchorMessageId }
    }
    val anchorChatId = when (anchorEntry) {
        is GroupedMessageItem.Single -> anchorEntry.message.chatId
        is GroupedMessageItem.Album -> anchorEntry.messages.firstOrNull()?.chatId
    }

    return ChatViewportCacheEntry(
        anchorMessageId = anchorMessageId,
        anchorAliasIds = anchorAliasIds,
        anchorOffsetPx = anchorItem.offset - info.viewportStartOffset,
        atBottom = false,
        readFully = false,
        topEndMessageId = groupedMessages.firstOrNull()?.firstMessageId?.takeIf { isOldestLoaded },
        anchorChatId = anchorChatId
    )
}

