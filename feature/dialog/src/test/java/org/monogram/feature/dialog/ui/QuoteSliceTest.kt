package org.monogram.feature.dialog.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.TextEntity

/**
 * A quote block must not draw an extra frame for its own entity: covering ranges
 * are its levels, duplicates are deeper levels, inner ranges are nested.
 */
class QuoteSliceTest {

    @Test
    fun blockOwnEntityDoesNotOpenANestedRegion() {
        val text = "quote"
        val slices = splitQuoteSlices(text, listOf(TextEntity("blockquote", 0, text.length)))
        assertEquals(1, slices.size)
        assertFalse(slices.single().nested)
        assertEquals(text, slices.single().text)
    }

    @Test
    fun duplicateBlockOwnRangesAreNotNestedRegions() {
        val text = "two"
        val slices = splitQuoteSlices(
            text,
            listOf(TextEntity("blockquote", 0, 3), TextEntity("blockquote", 0, 3)),
        )
        assertEquals(1, slices.size)
        assertFalse(slices.single().nested)
    }

    @Test
    fun innerQuoteBecomesANestedRegion() {
        val text = "outer\ninner"
        val slices = splitQuoteSlices(
            text,
            listOf(
                TextEntity("blockquote", 0, text.length),
                TextEntity("blockquote", 6, 5),
            ),
        )
        val nested = slices.single { it.nested }
        assertEquals("inner", nested.text)
        assertEquals(1, nested.level)
        assertTrue(nested.entities.none { it.kind == "blockquote" })
    }

    @Test
    fun deeperQuoteStaysAsAnInnerEntityOfTheNestedRegion() {
        val text = "outer\nmiddle\ninner"
        val slices = splitQuoteSlices(
            text,
            listOf(
                TextEntity("blockquote", 0, text.length),
                TextEntity("blockquote", 6, 12),
                TextEntity("blockquote", 13, 5),
            ),
        )
        val nested = slices.single { it.nested }
        assertEquals("middle\ninner", nested.text)
        assertEquals(1, nested.level)
        val deeper = nested.entities.single { it.kind == "blockquote" }
        assertEquals(7 to 5, deeper.offset to deeper.length)
    }

    @Test
    fun duplicateInnerRangesAreDeeperLevels() {
        val text = "outer\ninner"
        val slices = splitQuoteSlices(
            text,
            listOf(
                TextEntity("blockquote", 0, text.length),
                TextEntity("blockquote", 6, 5),
                TextEntity("blockquote", 6, 5),
            ),
        )
        val nested = slices.single { it.nested }
        assertEquals("inner", nested.text)
        assertEquals(2, nested.level)
    }

    @Test
    fun collapsedFlagTravelsWithTheNestedRegion() {
        val text = "outer\ninner"
        val slices = splitQuoteSlices(
            text,
            listOf(
                TextEntity("blockquote", 0, text.length),
                TextEntity("blockquote", 6, 5, "collapsed"),
            ),
        )
        assertTrue(slices.single { it.nested }.collapsed)
    }

    @Test
    fun inlineEntitiesOutsideQuotesAreKept() {
        val text = "hi"
        val slices = splitQuoteSlices(text, listOf(TextEntity("bold", 0, 2)))
        assertEquals(1, slices.size)
        assertEquals(listOf("bold"), slices.single().entities.map { it.kind })
    }
}
