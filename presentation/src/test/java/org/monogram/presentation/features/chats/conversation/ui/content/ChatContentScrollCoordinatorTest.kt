package org.monogram.presentation.features.chats.conversation.ui.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.presentation.features.chats.conversation.ScrollAlign

class ChatContentScrollCoordinatorTest {
    @Test
    fun `buildScrollToMessagePlan skips index jump when target already visible`() {
        val plan = buildScrollToMessagePlan(
            currentFirstVisibleIndex = 40,
            targetIndex = 44,
            totalItemsCount = 120,
            targetAlreadyVisible = true,
            staged = true
        )

        assertNull(plan.coarseIndex)
        assertFalse(plan.shouldAnimateToIndex)
    }

    @Test
    fun `buildScrollToMessagePlan keeps nearby targets direct without coarse stage`() {
        val plan = buildScrollToMessagePlan(
            currentFirstVisibleIndex = 40,
            targetIndex = 48,
            totalItemsCount = 120,
            targetAlreadyVisible = false,
            staged = true
        )

        assertNull(plan.coarseIndex)
        assertTrue(plan.shouldAnimateToIndex)
    }

    @Test
    fun `buildScrollToMessagePlan adds coarse stage for far forward targets`() {
        val plan = buildScrollToMessagePlan(
            currentFirstVisibleIndex = 10,
            targetIndex = 60,
            totalItemsCount = 120,
            targetAlreadyVisible = false,
            staged = true
        )

        assertEquals(50, plan.coarseIndex)
        assertTrue(plan.shouldAnimateToIndex)
    }

    @Test
    fun `buildScrollToMessagePlan adds coarse stage for far backward targets`() {
        val plan = buildScrollToMessagePlan(
            currentFirstVisibleIndex = 70,
            targetIndex = 15,
            totalItemsCount = 120,
            targetAlreadyVisible = false,
            staged = true
        )

        assertEquals(25, plan.coarseIndex)
        assertTrue(plan.shouldAnimateToIndex)
    }

    @Test
    fun `calculateAlignmentDelta centers item inside viewport`() {
        val delta = calculateAlignmentDelta(
            viewportStart = 0,
            viewportEnd = 1000,
            itemOffset = 700,
            itemSize = 200,
            align = ScrollAlign.Center
        )

        assertEquals(300f, delta, 0.001f)
    }

    @Test
    fun `calculateAlignmentDelta supports start and end alignment`() {
        val startDelta = calculateAlignmentDelta(
            viewportStart = 50,
            viewportEnd = 1050,
            itemOffset = 250,
            itemSize = 120,
            align = ScrollAlign.Start
        )
        val endDelta = calculateAlignmentDelta(
            viewportStart = 50,
            viewportEnd = 1050,
            itemOffset = 700,
            itemSize = 120,
            align = ScrollAlign.End
        )

        assertEquals(200f, startDelta, 0.001f)
        assertEquals((-230f), endDelta, 0.001f)
    }

    @Test
    fun `calculateBottomAlignmentDelta supports root chat and comments bottom alignment`() {
        val rootDelta = calculateBottomAlignmentDelta(
            viewportStart = 50,
            viewportEnd = 1050,
            itemOffset = 90,
            itemSize = 120,
            isComments = false
        )
        val commentsDelta = calculateBottomAlignmentDelta(
            viewportStart = 50,
            viewportEnd = 1050,
            itemOffset = 860,
            itemSize = 150,
            isComments = true
        )

        assertEquals(40f, rootDelta, 0.001f)
        assertEquals((-40f), commentsDelta, 0.001f)
    }

    @Test
    fun `visible bottom fast path is used only for small visible deltas`() {
        assertTrue(
            shouldUseVisibleBottomFastPath(
                targetAlreadyVisible = true,
                bottomAlignmentDelta = 80f
            )
        )
        assertFalse(
            shouldUseVisibleBottomFastPath(
                targetAlreadyVisible = true,
                bottomAlignmentDelta = 180f
            )
        )
        assertFalse(
            shouldUseVisibleBottomFastPath(
                targetAlreadyVisible = false,
                bottomAlignmentDelta = 40f
            )
        )
    }

    @Test
    fun `needsBottomAlignmentCorrection ignores already aligned bottom`() {
        assertFalse(needsBottomAlignmentCorrection(0.5f))
        assertTrue(needsBottomAlignmentCorrection(8f))
    }

    @Test
    fun `buildBottomCoarseScrollIndex skips staged jump when already near bottom`() {
        val coarseIndex = buildBottomCoarseScrollIndex(
            currentFirstVisibleIndex = 2,
            targetIndex = 0,
            totalItemsCount = 120,
            isComments = false
        )

        assertNull(coarseIndex)
    }

    @Test
    fun `buildBottomCoarseScrollIndex keeps staged jump for far positions`() {
        val rootCoarseIndex = buildBottomCoarseScrollIndex(
            currentFirstVisibleIndex = 70,
            targetIndex = 0,
            totalItemsCount = 120,
            isComments = false
        )
        val commentsCoarseIndex = buildBottomCoarseScrollIndex(
            currentFirstVisibleIndex = 10,
            targetIndex = 119,
            totalItemsCount = 120,
            isComments = true
        )

        assertEquals(8, rootCoarseIndex)
        assertEquals(111, commentsCoarseIndex)
    }

    @Test
    fun `chatContentLeadingItemsCount accounts for root loading-newer item`() {
        val leadingItems = chatContentLeadingItemsCount(
            isComments = false,
            showNavPadding = false,
            isLoadingOlder = false,
            isLoadingNewer = true,
            isAtBottom = false,
            hasMessages = true
        )

        assertEquals(1, leadingItems)
        assertEquals(4, groupedIndexToLazyIndex(groupedIndex = 3, leadingItemsCount = leadingItems))
        assertEquals(3, lazyIndexToGroupedIndex(lazyIndex = 4, leadingItemsCount = leadingItems))
    }

    @Test
    fun `chatContentLeadingItemsCount ignores root loading-newer at bottom`() {
        val leadingItems = chatContentLeadingItemsCount(
            isComments = false,
            showNavPadding = false,
            isLoadingOlder = false,
            isLoadingNewer = true,
            isAtBottom = true,
            hasMessages = true
        )

        assertEquals(0, leadingItems)
    }

    @Test
    fun `chatContentLeadingItemsCount accounts for comments root header and older loader`() {
        val leadingItems = chatContentLeadingItemsCount(
            isComments = true,
            showNavPadding = false,
            isLoadingOlder = true,
            isLoadingNewer = false,
            isAtBottom = false,
            hasMessages = true
        )

        assertEquals(2, leadingItems)
        assertEquals(2, groupedIndexToLazyIndex(groupedIndex = 0, leadingItemsCount = leadingItems))
        assertEquals(0, lazyIndexToGroupedIndex(lazyIndex = 2, leadingItemsCount = leadingItems))
    }
}
