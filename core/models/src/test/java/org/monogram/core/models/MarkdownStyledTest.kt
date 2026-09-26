package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownStyledTest {
    @Test
    fun stripsBoldItalicCode() {
        val styled = parseMarkdownToStyled("**bold** and *italic* and `code`")
        assertEquals("bold and italic and code", styled.text)
        assertEquals("bold", styled.entities[0].kind)
        assertEquals("italic", styled.entities[1].kind)
        assertEquals("code", styled.entities[2].kind)
        styled.entities.forEach { entity ->
            assertEquals(
                entity.kind,
                styled.text.substring(entity.offset, entity.offset + entity.length),
            )
        }
    }

    @Test
    fun headingQuoteFenceAndLink() {
        val styled = parseMarkdownToStyled(
            "# Title\n> quote\n```kt\nval x = 1\n```\n[go](https://t.me)",
        )
        assertTrue(styled.entities.any { it.kind == "heading" && it.url == "1" })
        assertTrue(styled.entities.any { it.kind == "blockquote" })
        val pre = styled.entities.first { it.kind == "pre" }
        assertEquals("kt", pre.url)
        assertEquals("val x = 1", styled.text.substring(pre.offset, pre.offset + pre.length))
        val link = styled.entities.first { it.kind == "text_url" }
        assertEquals("https://t.me", link.url)
        assertEquals("go", styled.text.substring(link.offset, link.offset + link.length))
    }

    @Test
    fun wrapBoldSelection() {
        val edit = wrapMarkdown("hello world", 0, 5, "**", "**")
        assertEquals("**hello** world", edit.text)
    }

    @Test
    fun wrapQuoteLines() {
        val edit = wrapQuote("one\ntwo", 0, 7)
        assertEquals("> one\n> two", edit.text)
    }

    @Test
    fun wrapQuoteNestsExistingQuotes() {
        val edit = wrapQuote("> one", 0, 5)
        assertEquals(">> one", edit.text)
    }

    @Test
    fun wrapQuoteNestsEachSelectedLine() {
        val edit = wrapQuote("> one\ntwo", 0, 9)
        assertEquals(">> one\n> two", edit.text)
    }

    @Test
    fun nestedQuoteMarkersAreOverlappingEntities() {
        val styled = parseMarkdownToStyled("> outer\n>> middle\n>>> inner")
        assertEquals("outer\nmiddle\ninner", styled.text)
        val quotes = styled.entities.filter { it.kind == "blockquote" }
        assertTrue(quotes.size >= 3)
        assertEquals(0, quotes.maxBy { it.length }.offset)
    }

    @Test
    fun boldItalicCombinations() {
        val cases = listOf(
            Triple("***both***", "both", listOf("bold" to 0, "italic" to 0)),
            Triple("*italic **bold** italic*", "italic bold italic", listOf("italic" to 0, "bold" to 7)),
            Triple("**bold *italic* bold**", "bold italic bold", listOf("bold" to 0, "italic" to 5)),
            Triple("__under **bold**__", "under bold", listOf("underline" to 0, "bold" to 6)),
            Triple("~~strike *italic*~~", "strike italic", listOf("strike" to 0, "italic" to 7)),
            Triple("||spoiler **bold**||", "spoiler bold", listOf("spoiler" to 0, "bold" to 8)),
            Triple("**bold __under__**", "bold under", listOf("bold" to 0, "underline" to 5)),
        )
        for ((raw, text, expected) in cases) {
            val styled = parseMarkdownToStyled(raw)
            assertEquals(text, styled.text)
            expected.forEach { (kind, offset) ->
                assertTrue(
                    "$raw missing $kind at $offset: ${styled.entities}",
                    styled.entities.any { it.kind == kind && it.offset == offset },
                )
            }
        }
    }

    @Test
    fun unmatchedMarkersStay() {
        val styled = parseMarkdownToStyled("star * leftover")
        assertEquals("star * leftover", styled.text)
        assertTrue(styled.entities.isEmpty())
    }

    @Test
    fun emptyBoldMarkersStrip() {
        val styled = parseMarkdownToStyled("****")
        assertEquals("", styled.text)
    }

    @Test
    fun mappedOffsetsHideBoldMarkers() {
        val mapped = parseMarkdownMapped("**hi**")
        assertEquals("hi", mapped.styled.text)
        assertEquals(0, mapped.origToDisp[0])
        assertEquals(0, mapped.origToDisp[2])
        assertEquals(2, mapped.origToDisp[4])
        assertEquals(2, mapped.origToDisp[6])
    }
}
