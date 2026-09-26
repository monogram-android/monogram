package org.monogram.feature.dialog.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.markup.MathSpan
import org.monogram.core.models.TextEntity

class MathSpansTest {
    private val text = "before \$x^2\$ after"
    private val span = MathSpan(7, 12, false, "x^2")

    @Test
    fun hiddenSpoilerCannotBecomeAnImage() {
        val spoiler = listOf(TextEntity("spoiler", 7, 5))
        assertTrue(eligibleMathSpans(text, listOf(span), spoiler, false).isEmpty())
        assertEquals(listOf(span), eligibleMathSpans(text, listOf(span), spoiler, true))
    }

    @Test
    fun codeLinksAndCustomEmojiKeepTheirOriginalContent() {
        listOf("code", "pre", "url", "text_url", "custom_emoji").forEach { kind ->
            assertTrue(eligibleMathSpans(text, listOf(span), listOf(TextEntity(kind, 8, 1)), true).isEmpty())
        }
    }

    @Test
    fun ignoresInvalidOrOverlappingRanges() {
        assertEquals(listOf(span), eligibleMathSpans(
            text, listOf(span.copy(start = -1), span, span, span.copy(end = 100)), emptyList(), false,
        ))
    }

    @Test
    fun multilineDisplayMathIsEligibleButHiddenSpoilersStayProtected() {
        val multiline = "\$\$\nx^2\n\$\$"
        val display = MathSpan(0, multiline.length, true, "x^2")
        assertEquals(listOf(display), eligibleMathSpans(multiline, listOf(display), emptyList(), false))
        assertTrue(eligibleMathSpans(multiline, listOf(display), listOf(TextEntity("spoiler", 0, multiline.length)), false).isEmpty())
    }
}
