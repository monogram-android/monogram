package org.monogram.presentation.features.chats.currentChat.chatContent

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.monogram.domain.models.ChatViewportCacheEntry
import org.monogram.presentation.features.chats.currentChat.ScrollAlign
import kotlin.math.abs

@Immutable
internal data class BottomVisibilitySnapshot(
    val isAtBottom: Boolean,
    val isNearBottom: Boolean,
    val unreadCount: Int
)

internal suspend fun LazyListState.scrollToMessageIndex(
    index: Int,
    align: ScrollAlign,
    animated: Boolean,
    staged: Boolean
) {
    val total = layoutInfo.totalItemsCount
    if (total <= 0) return

    val boundedIndex = index.coerceIn(0, total - 1)
    val distance = abs(firstVisibleItemIndex - boundedIndex)

    if (staged && distance > 20) {
        val coarseIndex = when {
            boundedIndex > firstVisibleItemIndex -> (boundedIndex - 10).coerceAtLeast(0)
            boundedIndex < firstVisibleItemIndex -> (boundedIndex + 10).coerceAtMost(total - 1)
            else -> boundedIndex
        }
        scrollToItem(coarseIndex)
    }

    scrollToItem(boundedIndex)

    val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == boundedIndex } ?: return
    val viewportStart = layoutInfo.viewportStartOffset
    val viewportEnd = layoutInfo.viewportEndOffset
    val viewportCenter = (viewportStart + viewportEnd) / 2

    val targetPosition = when (align) {
        ScrollAlign.Start -> viewportStart
        ScrollAlign.Center -> viewportCenter - (itemInfo.size / 2)
        ScrollAlign.End -> viewportEnd - itemInfo.size
    }
    val delta = (itemInfo.offset - targetPosition).toFloat()

    if (abs(delta) > 1f) {
        if (animated) {
            animateScrollBy(delta)
        } else {
            scrollBy(delta)
        }
    }
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

internal suspend fun LazyListState.scrollToChatBottomStaged(
    isComments: Boolean,
    animated: Boolean
) {
    val total = layoutInfo.totalItemsCount
    if (total <= 0) return

    val targetIndex = if (isComments) total - 1 else 0
    val distance = abs(firstVisibleItemIndex - targetIndex)

    if (distance > 24) {
        val coarse = if (isComments) {
            (targetIndex - 8).coerceAtLeast(0)
        } else {
            (targetIndex + 8).coerceAtMost(total - 1)
        }
        scrollToItem(coarse)
    }

    if (animated) {
        animateScrollToItem(targetIndex)
    } else {
        scrollToItem(targetIndex)
    }

    val targetInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
    if (targetInfo != null) {
        val delta = if (isComments) {
            ((targetInfo.offset + targetInfo.size) - layoutInfo.viewportEndOffset).toFloat()
        } else {
            (targetInfo.offset - layoutInfo.viewportStartOffset).toFloat()
        }
        if (abs(delta) > 1f) {
            scrollBy(delta)
        }
    }

    scrollToItem(targetIndex)
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

    scrollToItem(0)
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

internal fun buildViewportSnapshot(
    scrollState: LazyListState,
    groupedMessages: List<GroupedMessageItem>,
    isComments: Boolean,
    isLatestLoaded: Boolean,
    isLoadingOlder: Boolean,
    isLoadingNewer: Boolean,
    isAtBottom: Boolean,
    showNavPadding: Boolean
): ChatViewportCacheEntry? {
    if (groupedMessages.isEmpty()) {
        return ChatViewportCacheEntry(atBottom = true)
    }

    val atBottomNow = scrollState.isAtBottom(
        isComments = isComments,
        isLatestLoaded = isLatestLoaded
    )
    if (atBottomNow) {
        return ChatViewportCacheEntry(atBottom = true)
    }

    val leadingItems = chatContentLeadingItemsCount(
        isComments = isComments,
        showNavPadding = showNavPadding,
        isLoadingOlder = isLoadingOlder,
        isLoadingNewer = isLoadingNewer,
        isAtBottom = isAtBottom,
        hasMessages = groupedMessages.isNotEmpty()
    )
    val info = scrollState.layoutInfo
    val anchorItem = info.visibleItemsInfo.firstOrNull { itemInfo ->
        val groupedIndex = lazyIndexToGroupedIndex(itemInfo.index, leadingItems)
        groupedIndex in groupedMessages.indices
    } ?: return null

    val groupedIndex = lazyIndexToGroupedIndex(anchorItem.index, leadingItems)
    val anchorMessageId = groupedMessages.getOrNull(groupedIndex)?.firstMessageId ?: return null

    return ChatViewportCacheEntry(
        anchorMessageId = anchorMessageId,
        anchorOffsetPx = anchorItem.offset - info.viewportStartOffset,
        atBottom = false
    )
}
