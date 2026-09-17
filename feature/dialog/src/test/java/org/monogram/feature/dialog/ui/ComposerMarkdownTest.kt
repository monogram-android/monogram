package org.monogram.feature.dialog.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerMarkdownTest {
    @Test
    fun insertionReplacesForwardAndReversedSelection() {
        val emoji = "\uD83D\uDE00"
        for (range in listOf(TextRange(1, 4), TextRange(4, 1))) {
            val next = insertComposerText(TextFieldValue("abcde", range), emoji)
            assertEquals("a${emoji}e", next.text)
            assertEquals(TextRange(3), next.selection)
        }
        val next = insertComposerText(TextFieldValue("ab", TextRange(1)), emoji)
        assertEquals("a${emoji}b", next.text)
        assertEquals(TextRange(3), next.selection)
    }

    @Test
    fun wrapKeepsInnerSelection() {
        val value = TextFieldValue("hello world", TextRange(0, 5))
        val next = wrapMarkdown(value, "**", "**")
        assertEquals("**hello** world", next.text)
        assertEquals(TextRange(2, 7), next.selection)
    }

    @Test
    fun selectedPlainStripsBoldMarkers() {
        val value = TextFieldValue("**hello**", TextRange(0, 9))
        assertEquals("hello", selectedComposerPlain(value))
    }

    @Test
    fun deleteSelectionCutsRange() {
        val value = TextFieldValue("hello world", TextRange(0, 6))
        val next = deleteComposerSelection(value)
        assertEquals("world", next.text)
        assertEquals(TextRange(0), next.selection)
    }

    @Test
    fun selectAllCoversDraft() {
        val value = TextFieldValue("ab", TextRange(1))
        assertEquals(TextRange(0, 2), selectAllComposer(value).selection)
    }

    @Test
    fun collapseSelectionKeepsCursorAtEndOfRange() {
        val value = TextFieldValue("hello", TextRange(1, 4))
        val next = collapseComposerSelection(value)
        assertEquals("hello", next.text)
        assertEquals(TextRange(4), next.selection)
        val collapsed = TextFieldValue("hello", TextRange(2))
        assertEquals(collapsed, collapseComposerSelection(collapsed))
    }
}
