package org.monogram.core.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dialog placeholder has to fit the row it stands in for, otherwise the chat list jumps when the
 * first page arrives. These invariants are what [ChatListSkeleton] and the real row share.
 */
class ChatRowSkeletonMetricsTest {
    private val verticalInsets = ChatRowMetrics.ContainerPaddingV + ChatRowMetrics.ContentPaddingV

    @Test
    fun placeholderAvatarFitsInsideTheLoadedRowHeight() {
        assertTrue(ChatRowMetrics.AvatarSize + verticalInsets * 2 <= ChatRowMetrics.Height)
    }

    @Test
    fun placeholderTextLinesFitInsideTheLoadedRowHeight() {
        val textBlock = ChatRowMetrics.TitleLineHeight +
            ChatRowMetrics.LineSpacing +
            ChatRowMetrics.PreviewLineHeight

        assertTrue(textBlock + verticalInsets * 2 <= ChatRowMetrics.Height)
    }

    @Test
    fun placeholderTrailingColumnFitsInsideTheLoadedRowHeight() {
        val trailing = ChatRowMetrics.TitleLineHeight +
            ChatRowMetrics.RightSpacing +
            SkeletonBadgeHeight

        assertTrue(trailing + verticalInsets * 2 <= ChatRowMetrics.Height)
    }

    @Test
    fun lineBoxesFollowTheChatRowTypography() {
        // titleMedium is 16sp/24sp and bodyMedium 14sp/20sp; the row boxes are those line heights.
        assertEquals(24.dp, ChatRowMetrics.TitleLineHeight)
        assertEquals(20.dp, ChatRowMetrics.PreviewLineHeight)
        assertEquals(56.dp, ChatRowMetrics.AvatarSize)
        assertEquals(72.dp, ChatRowMetrics.Height)
    }

    @Test
    fun placeholderCoversTheWholePane() {
        val row = ChatRowMetrics.Height.value

        // A pane that is not a whole number of rows still ends under a placeholder row.
        assertEquals(1, chatSkeletonRowCount(row, row))
        assertEquals(2, chatSkeletonRowCount(row + 1f, row))
        assertEquals(10, chatSkeletonRowCount(row * 10, row))
        assertEquals(20, chatSkeletonRowCount(row * 19 + 1f, row))
        assertTrue(chatSkeletonRowCount(row * 19.5f, row) * row >= row * 19.5f)
    }

    @Test
    fun placeholderNeverExplodesOnUnboundedPanes() {
        // An unbounded parent (a scroll container) reports an infinite height: fall back, never
        // repeat a huge row count.
        assertEquals(ChatSkeletonFallbackRows, chatSkeletonRowCount(Float.POSITIVE_INFINITY, 72f))
        // A pane taller than the cap is bounded too.
        assertTrue(chatSkeletonRowCount(100_000f, 72f) <= 40)
        assertEquals(1, chatSkeletonRowCount(0f, 72f))
        assertEquals(1, chatSkeletonRowCount(500f, 0f))
    }
}
