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
