package org.monogram.feature.dialog.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.markup.CodeHighlight

class CodeHighlightTest {
    @Test
    fun appliesKeywordSpan() {
        val annotated = highlightToAnnotatedString(
            code = "fn x",
            spans = listOf(CodeHighlight(0, 2, "keyword")),
            colorForScope = { if (it == "keyword") Color.Red else Color.Black },
        )
        assertEquals("fn x", annotated.text)
        assertTrue(annotated.spanStyles.any { it.start == 0 && it.end == 2 })
    }

    @Test
    fun keywordUsesPrimary() {
        val color = highlightScopeColor(
            scope = "keyword.control",
            onSurface = Color.Gray,
            primary = Color.Blue,
            secondary = Color.Green,
            tertiary = Color.Yellow,
            onSurfaceVariant = Color.DarkGray,
        )
        assertEquals(Color.Blue, color)
    }
}
