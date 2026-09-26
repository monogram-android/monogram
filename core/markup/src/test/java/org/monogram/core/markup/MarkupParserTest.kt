package org.monogram.core.markup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.StyledText
import org.monogram.core.models.TextEntity
import org.monogram.markup.NativeHighlightSpan
import org.monogram.markup.NativeMarkupEntity
import org.monogram.markup.NativeMathSpan
import org.monogram.markup.NativeStyledMarkup
import org.monogram.markup.NativeMarkupBlock
import org.monogram.core.models.RichBlock

class MarkupParserTest {
    @Test
    fun nativeBlocksPreserveLocalEntitiesAndTableCells() {
        val heading = NativeMarkupBlock(
            "heading", "Title", listOf(NativeMarkupEntity("bold", 0, 5)), null, 2,
            emptyList(), emptyList(),
        ).toRichBlock()
        assertEquals(RichBlock.Heading("Title", 2, listOf(TextEntity("bold", 0, 5))), heading)
        val table = NativeMarkupBlock(
            "table", "", emptyList(), null, 0, listOf("A", "B"), listOf(listOf("1", "2")),
        ).toRichBlock()
        assertEquals(RichBlock.Table(listOf("A", "B"), listOf(listOf("1", "2"))), table)
        val tasks = NativeMarkupBlock(
            "tasks", "open\ndone", emptyList(), null, 0,
            emptyList(), listOf(listOf("0", "open"), listOf("1", "done")),
        ).toRichBlock() as RichBlock.TaskList
        assertEquals("open", tasks.items[0].text)
        assertTrue(tasks.items[1].done)
        val quote = NativeMarkupBlock(
            "quote", "inner", listOf(NativeMarkupEntity("blockquote", 0, 5, "collapsed")), "collapsed", 2,
            emptyList(), emptyList(),
        ).toRichBlock() as RichBlock.Quote
        assertTrue(quote.collapsed)
        assertEquals(2, quote.level)
    }

    @Test
    fun mapsNativeEntitiesToStyledText() {
        val styled = NativeStyledMarkup(
            text = "bold",
            entities = listOf(
                NativeMarkupEntity(kind = "bold", offset = 0, length = 4, extra = null),
            ),
        ).toStyledText()
        assertEquals("bold", styled.text)
        assertEquals(listOf(TextEntity("bold", 0, 4, url = null)), styled.entities)
    }

    @Test
    fun mapsCustomEmojiIdIntoUrl() {
        val entity = NativeMarkupEntity(
            kind = "custom_emoji",
            offset = 0,
            length = 2,
            extra = "42",
        ).toTextEntity()
        assertEquals("custom_emoji", entity.kind)
        assertEquals("42", entity.url)
    }

    @Test
    fun fakeParserDoesNotTouchUniffi() {
        val parser = FakeMarkupParser(
            parse = { raw ->
                StyledText(text = "hi", entities = listOf(TextEntity("bold", 0, 2)))
                    .also { assertEquals("**hi**", raw) }
            },
            highlight = { _, lang ->
                assertEquals("rust", lang)
                listOf(CodeHighlight(0, 2, "keyword"))
            },
            math = { listOf(MathSpan(0, 5, display = false, source = "a+b")) },
            languages = listOf("rust"),
        )
        assertEquals("hi", parser.parseTelegramMarkdown("**hi**").text)
        assertEquals("keyword", parser.highlightCode("fn", "rust").single().scope)
        assertEquals("a+b", parser.extractMath("\$a+b\$").single().source)
        assertEquals(listOf("rust"), parser.supportedHighlightLanguages())
    }

    @Test
    fun kotlinParserExtractsMath() {
        val spans = KotlinMarkupParser().extractMath("see \$a+b\$ and \$\$x^2\$\$")
        assertEquals(2, spans.size)
        assertEquals("a+b", spans[0].source)
        assertTrue(spans[1].display)
    }

    @Test
    fun forSendDropsHeadingAndKeepsBold() {
        val styled = StyledText(
            text = "Title",
            entities = listOf(
                TextEntity("heading", 0, 5, url = "1"),
                TextEntity("bold", 0, 5),
            ),
        ).forSend()
        assertEquals(listOf(TextEntity("bold", 0, 5)), styled.entities)
    }

    @Test
    fun forSendDropsCustomEmojiWhenNotPremium() {
        val styled = StyledText(
            text = "x",
            entities = listOf(TextEntity("custom_emoji", 0, 1, url = "42")),
        ).forSend(isPremium = false)
        assertTrue(styled.entities.isEmpty())
        val premium = StyledText(
            text = "x",
            entities = listOf(TextEntity("custom_emoji", 0, 1, url = "42")),
        ).forSend(isPremium = true)
        assertEquals(1, premium.entities.size)
        val free = StyledText(
            text = "x",
            entities = listOf(TextEntity("custom_emoji", 0, 1, url = "7")),
        ).forSend(isPremium = false, freeCustomEmojiUrls = setOf("7"))
        assertEquals(1, free.entities.size)
    }

    @Test
    fun forSendRejectsInvalidUtf16BoundsAndDocumentIds() {
        val bold = TextEntity("bold", 0, 2)
        val styled = StyledText("\uD83D\uDE00x", listOf(
            bold,
            TextEntity("italic", -1, 1),
            TextEntity("italic", 0, Int.MAX_VALUE),
            TextEntity("italic", 1, 1),
            TextEntity("italic", 0, 1),
            TextEntity("italic", 3, 1),
            TextEntity("custom_emoji", 0, 2, "invalid"),
            TextEntity("custom_emoji", 0, 2, "0"),
        )).forSend(isPremium = true)
        assertEquals(listOf(bold), styled.entities)
    }

    @Test
    fun forSendEnforcesZeroAndPositiveCustomEmojiLimits() {
        val styled = StyledText("xx", listOf(
            TextEntity("custom_emoji", 0, 1, "42"),
            TextEntity("custom_emoji", 1, 1, "42"),
        ))
        assertTrue(styled.forSend(isPremium = true, maxCustomEmoji = 0).entities.isEmpty())
        assertEquals(1, styled.forSend(isPremium = true, maxCustomEmoji = 1).entities.size)
    }

    @Test
    fun highlightAndMathMapping() {
        val highlight = NativeHighlightSpan(0, 2, "keyword").toCodeHighlight()
        assertEquals(CodeHighlight(0, 2, "keyword"), highlight)
        val math = NativeMathSpan(1, 6, true, "x^2").toMathSpan()
        assertTrue(math.display)
        assertEquals("x^2", math.source)
    }
}
