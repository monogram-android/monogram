package org.monogram.feature.dialog.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.core.models.parseMarkdownMapped

class MarkdownVisualTest {
    @Test
    fun cursorInsideBoldMapsPastMarkers() {
        val mapped = parseMarkdownMapped("**hi**")
        val mapping = MarkdownOffsetMapping(mapped.origToDisp)
        assertEquals(0, mapping.originalToTransformed(2))
        assertEquals(2, mapping.originalToTransformed(4))
        assertEquals(2, mapping.transformedToOriginal(0))
        assertEquals(4, mapping.transformedToOriginal(2))
        assertEquals("hi", parseMarkdownMapped("**hi**").styled.text)
    }
}
