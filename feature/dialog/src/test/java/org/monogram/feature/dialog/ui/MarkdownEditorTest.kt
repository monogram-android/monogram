package org.monogram.feature.dialog.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownEditorTest {
    @Test
    fun expandButtonAppearsForLongOrStructuredText() {
        org.junit.Assert.assertFalse(shouldShowFullScreenEditor(""))
        org.junit.Assert.assertFalse(shouldShowFullScreenEditor("\n".repeat(60)))
        org.junit.Assert.assertFalse(shouldShowFullScreenEditor("a".repeat(50)))
        org.junit.Assert.assertFalse(shouldShowFullScreenEditor("\uD83D\uDE00".repeat(50)))
        assertTrue(shouldShowFullScreenEditor("a".repeat(51)))
        assertTrue(shouldShowFullScreenEditor("\uD83D\uDE00".repeat(51)))
        assertTrue(shouldShowFullScreenEditor("one\ntwo"))
        assertTrue(shouldShowFullScreenEditor("```kotlin"))
        org.junit.Assert.assertFalse(shouldShowFullScreenEditor("**hello**"))
    }

    @Test
    fun expandsOnlyStructuredDrafts() {
        assertTrue(needsFullScreenEditor("```kotlin"))
        assertTrue(needsFullScreenEditor("before\n\$\$x^2\$\$"))
        assertTrue(needsFullScreenEditor("**title**\nfirst\nsecond\nthird"))
        org.junit.Assert.assertFalse(needsFullScreenEditor("**hello**"))
        org.junit.Assert.assertFalse(needsFullScreenEditor("one\ntwo\nthree\nfour"))
        org.junit.Assert.assertFalse(needsFullScreenEditor("price \$20 and inline `code`"))
    }

    @Test
    fun codeBlockKeepsSurroundingTextAndSelection() {
        val result = wrapCodeBlock(TextFieldValue("a code b", TextRange(2, 6)), "kotlin")
        assertEquals("a \n```kotlin\ncode\n```\n b", result.text)
        assertEquals("code", result.text.substring(result.selection.min, result.selection.max))
    }

    @Test
    fun undoDropsImeCompositionAndClearsRedoOnlyForTextEdits() {
        val current = TextFieldValue("a", composition = TextRange(0, 1))
        val undo = mutableListOf<TextFieldValue>()
        val redo = mutableListOf(TextFieldValue("previous"))
        pushEditorUndo(current, current.copy(selection = TextRange(0)), undo, redo)
        assertEquals(1, redo.size)
        pushEditorUndo(current, TextFieldValue("ab"), undo, redo)
        assertEquals(null, undo.single().composition)
        assertTrue(redo.isEmpty())
    }

    @Test
    fun applyKeepsLiveWrap() {
        val snapshot = TextFieldValue("hello", TextRange(0, 5))
        val edited = wrapMarkdown(snapshot, "**")
        val result = finishMarkdownEditor(snapshot, edited, apply = true)
        assertEquals("**hello**", result.text)
    }

    @Test
    fun cancelRestoresSnapshot() {
        val snapshot = TextFieldValue("hello")
        val edited = wrapMarkdown(snapshot.copy(selection = TextRange(0, 5)), "||")
        val result = finishMarkdownEditor(snapshot, edited, apply = false)
        assertEquals("hello", result.text)
    }

    @Test
    fun italicAndCodeWraps() {
        val value = TextFieldValue("x", TextRange(0, 1))
        assertEquals("*x*", wrapMarkdown(value, "*").text)
        assertEquals("`x`", wrapMarkdown(value, "`").text)
    }

    @Test
    fun undoStackRecordsTextChange() {
        val undo = mutableListOf<TextFieldValue>()
        val redo = mutableListOf<TextFieldValue>()
        val current = TextFieldValue("a")
        pushEditorUndo(current, TextFieldValue("**a**"), undo, redo)
        assertEquals(1, undo.size)
        assertEquals("a", undo.single().text)
        assertTrue(redo.isEmpty())
    }

    /** The oracle is `Regex("\\S+")`; non-breaking and unicode spaces are not separators. */
    @Test
    fun wordCountCountsNonWhitespaceRuns() {
        val samples = listOf(
            "",
            "   ",
            "one",
            "one two",
            "  one   two  ",
            "one\ntwo\n\nthree",
            "one\ttwo\r\nthree",
            "one\u000Btwo\u000Cthree",
            "one\u00A0two",
            "one\u2003two",
            "one\u2028two",
            "\uD83D\uDE00 \uD83D\uDE00",
        )
        for (sample in samples) {
            assertEquals(
                "mismatch for ${sample.replace("\n", "\\n")}",
                Regex("\\S+").findAll(sample).count(),
                countEditorWords(sample),
            )
        }
    }
}
