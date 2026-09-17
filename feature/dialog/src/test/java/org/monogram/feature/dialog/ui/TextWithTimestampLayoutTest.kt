package org.monogram.feature.dialog.ui

import androidx.compose.ui.text.style.ResolvedTextDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class TextWithTimestampLayoutTest {
    @Test
    fun testUsesInlinePlacementAtTrailingEdgeOfWiderLtrText() {
        val placement = calculateFooterPlacement(
            textLayoutInfo = layoutInfo(
                width = 220,
                height = 48,
                lastLineLeft = 0f,
                lastLineRight = 120f,
                lastLineBottom = 48f,
                direction = ResolvedTextDirection.Ltr
            ),
            textWidth = 220,
            textHeight = 48,
            footerWidth = 60,
            footerHeight = 14,
            maxWidth = 260,
            horizontalPadding = 8,
            stackedTopPadding = 2
        )

        assertEquals(FooterPlacementMode.Inline, placement.mode)
        assertEquals(160, placement.footerX)
        assertEquals(34, placement.footerY)
        assertEquals(220, placement.layoutWidth)
        assertEquals(48, placement.layoutHeight)
    }

    @Test
    fun testFallsBackToStackedPlacementWhenLastLineHasNoSpace() {
        val placement = calculateFooterPlacement(
            textLayoutInfo = layoutInfo(
                width = 220,
                height = 48,
                lastLineLeft = 0f,
                lastLineRight = 210f,
                lastLineBottom = 48f,
                direction = ResolvedTextDirection.Ltr
            ),
            textWidth = 220,
            textHeight = 48,
            footerWidth = 60,
            footerHeight = 14,
            maxWidth = 240,
            horizontalPadding = 8,
            stackedTopPadding = 2
        )

        assertEquals(FooterPlacementMode.Stacked, placement.mode)
        assertEquals(160, placement.footerX)
        assertEquals(50, placement.footerY)
        assertEquals(220, placement.layoutWidth)
        assertEquals(64, placement.layoutHeight)
    }

    @Test
    fun testExpandedBubblePinsTimeToTrailingEdge() {
        val placement = calculateFooterPlacement(
            textLayoutInfo = layoutInfo(
                width = 80,
                height = 20,
                lastLineLeft = 0f,
                lastLineRight = 72f,
                lastLineBottom = 20f,
                direction = ResolvedTextDirection.Ltr,
            ),
            textWidth = 80,
            textHeight = 20,
            footerWidth = 56,
            footerHeight = 14,
            minWidth = 300,
            maxWidth = 324,
            horizontalPadding = 8,
            stackedTopPadding = 2,
        )
        assertEquals(FooterPlacementMode.Inline, placement.mode)
        assertEquals(244, placement.footerX)
        assertEquals(300, placement.layoutWidth)
    }

    private fun layoutInfo(
        width: Int,
        height: Int,
        lastLineLeft: Float,
        lastLineRight: Float,
        lastLineBottom: Float,
        direction: ResolvedTextDirection
    ) = MessageTextLayoutInfo(
        width = width,
        height = height,
        lineCount = 2,
        lastLineLeft = lastLineLeft,
        lastLineRight = lastLineRight,
        lastLineBottom = lastLineBottom,
        lastLineDirection = direction
    )
}
