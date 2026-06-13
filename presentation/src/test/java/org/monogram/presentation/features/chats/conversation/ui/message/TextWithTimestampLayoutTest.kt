package org.monogram.presentation.features.chats.conversation.ui.message

import androidx.compose.ui.text.style.ResolvedTextDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextWithTimestampLayoutTest {
    @Test
    fun `disables inline footer when reply is present`() {
        assertFalse(
            shouldUseInlineFooter(
                hasReply = true,
                hasForward = false,
                hasLinkPreview = false,
                isBigEmoji = false
            )
        )
    }

    @Test
    fun `disables inline footer when forward is present`() {
        assertFalse(
            shouldUseInlineFooter(
                hasReply = false,
                hasForward = true,
                hasLinkPreview = false,
                isBigEmoji = false
            )
        )
    }

    @Test
    fun `keeps inline footer for plain text without wide header blocks`() {
        assertTrue(
            shouldUseInlineFooter(
                hasReply = false,
                hasForward = false,
                hasLinkPreview = false,
                isBigEmoji = false
            )
        )
    }

    @Test
    fun `uses inline placement at trailing edge of wider ltr text`() {
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
    fun `expands layout width for short ltr messages with inline footer`() {
        val placement = calculateFooterPlacement(
            textLayoutInfo = layoutInfo(
                width = 84,
                height = 22,
                lastLineLeft = 0f,
                lastLineRight = 84f,
                lastLineBottom = 22f,
                direction = ResolvedTextDirection.Ltr
            ),
            textWidth = 84,
            textHeight = 22,
            footerWidth = 54,
            footerHeight = 14,
            maxWidth = 180,
            horizontalPadding = 8,
            stackedTopPadding = 2
        )

        assertEquals(FooterPlacementMode.Inline, placement.mode)
        assertEquals(92, placement.footerX)
        assertEquals(146, placement.layoutWidth)
    }

    @Test
    fun `uses inline placement at trailing edge when parent enforces wider bubble`() {
        val placement = calculateFooterPlacement(
            textLayoutInfo = layoutInfo(
                width = 128,
                height = 24,
                lastLineLeft = 0f,
                lastLineRight = 88f,
                lastLineBottom = 24f,
                direction = ResolvedTextDirection.Ltr
            ),
            textWidth = 128,
            textHeight = 24,
            footerWidth = 52,
            footerHeight = 14,
            minWidth = 220,
            maxWidth = 260,
            horizontalPadding = 8,
            stackedTopPadding = 2
        )

        assertEquals(FooterPlacementMode.Inline, placement.mode)
        assertEquals(168, placement.footerX)
        assertEquals(220, placement.layoutWidth)
    }

    @Test
    fun `falls back to stacked placement when footer does not fit after last ltr line`() {
        val placement = calculateFooterPlacement(
            textLayoutInfo = layoutInfo(
                width = 220,
                height = 48,
                lastLineLeft = 0f,
                lastLineRight = 190f,
                lastLineBottom = 48f,
                direction = ResolvedTextDirection.Ltr
            ),
            textWidth = 220,
            textHeight = 48,
            footerWidth = 40,
            footerHeight = 14,
            maxWidth = 220,
            horizontalPadding = 8,
            stackedTopPadding = 2
        )

        assertEquals(FooterPlacementMode.Stacked, placement.mode)
        assertEquals(180, placement.footerX)
        assertEquals(50, placement.footerY)
        assertEquals(64, placement.layoutHeight)
    }

    @Test
    fun `uses inline placement on rtl line when footer fits before the line start`() {
        val placement = calculateFooterPlacement(
            textLayoutInfo = layoutInfo(
                width = 220,
                height = 48,
                lastLineLeft = 96f,
                lastLineRight = 220f,
                lastLineBottom = 48f,
                direction = ResolvedTextDirection.Rtl
            ),
            textWidth = 220,
            textHeight = 48,
            footerWidth = 60,
            footerHeight = 14,
            maxWidth = 220,
            horizontalPadding = 8,
            stackedTopPadding = 2
        )

        assertEquals(FooterPlacementMode.Inline, placement.mode)
        assertEquals(28, placement.footerX)
        assertEquals(34, placement.footerY)
    }

    @Test
    fun `falls back to stacked placement when text layout info is unavailable`() {
        val placement = calculateFooterPlacement(
            textLayoutInfo = null,
            textWidth = 180,
            textHeight = 36,
            footerWidth = 52,
            footerHeight = 14,
            maxWidth = 220,
            horizontalPadding = 8,
            stackedTopPadding = 2
        )

        assertEquals(FooterPlacementMode.Stacked, placement.mode)
        assertEquals(128, placement.footerX)
        assertEquals(38, placement.footerY)
        assertEquals(180, placement.layoutWidth)
        assertEquals(52, placement.layoutHeight)
    }

    @Test
    fun `uses stacked placement at trailing edge when parent enforces wider bubble`() {
        val placement = calculateFooterPlacement(
            textLayoutInfo = null,
            textWidth = 180,
            textHeight = 36,
            footerWidth = 52,
            footerHeight = 14,
            minWidth = 240,
            maxWidth = 260,
            horizontalPadding = 8,
            stackedTopPadding = 2
        )

        assertEquals(FooterPlacementMode.Stacked, placement.mode)
        assertEquals(188, placement.footerX)
        assertEquals(240, placement.layoutWidth)
        assertEquals(52, placement.layoutHeight)
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
