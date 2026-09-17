package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RichTextTest {
    @Test
    fun splitsByPreAndBlockquoteEntities() {
        val text = "before\nhello world\nafter"
        val entities = listOf(
            TextEntity(kind = "pre", offset = 7, length = 11, url = "kotlin"),
        )
        val blocks = splitRichText(text, entities)
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is RichBlock.Paragraph)
        assertEquals("before", (blocks[0] as RichBlock.Paragraph).text)
        assertTrue(blocks[1] is RichBlock.Code)
        assertEquals("hello world", (blocks[1] as RichBlock.Code).text)
        assertEquals("kotlin", (blocks[1] as RichBlock.Code).language)
        assertTrue(blocks[2] is RichBlock.Paragraph)
        assertEquals("after", (blocks[2] as RichBlock.Paragraph).text)
    }

    @Test
    fun keepsCustomEmojiEntitiesOnPlainParagraph() {
        val text = "\uD83D\uDE05"
        val entities = listOf(
            TextEntity(kind = "custom_emoji", offset = 0, length = 2, url = "5453997670030907974"),
        )
        val blocks = splitRichText(text, entities)
        assertEquals(1, blocks.size)
        val paragraph = blocks[0] as RichBlock.Paragraph
        assertEquals(entities, paragraph.entities)
    }

    @Test
    fun splitsMarkdownFencesFallback() {
        val md = "Intro\n```rust\nfn main() {}\n```\nOutro"
        val blocks = splitRichText(md)
        assertEquals(3, blocks.size)
        assertEquals("Intro", (blocks[0] as RichBlock.Paragraph).text)
        assertEquals("fn main() {}", (blocks[1] as RichBlock.Code).text)
        assertEquals("rust", (blocks[1] as RichBlock.Code).language)
        assertEquals("Outro", (blocks[2] as RichBlock.Paragraph).text)
    }

    @Test
    fun splitsMarkdownTable() {
        val md = "| Col A | Col B |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |"
        val blocks = splitRichText(md)
        assertEquals(1, blocks.size)
        val table = blocks.single() as RichBlock.Table
        assertEquals(listOf("Col A", "Col B"), table.headers)
        assertEquals(listOf(listOf("1", "2"), listOf("3", "4")), table.rows)
    }

    @Test
    fun splitsMarkdownLists() {
        val md = "- one\n- two\n1. three"
        val blocks = splitRichText(md)
        assertEquals(1, blocks.size)
        assertEquals("• one\n• two\n• three", (blocks.single() as RichBlock.Paragraph).text)
    }

    @Test
    fun splitsMarkdownHeadings() {
        val md = "# Title\nbody"
        val blocks = splitRichText(md)
        assertEquals(2, blocks.size)
        val heading = blocks[0] as RichBlock.Heading
        assertEquals(1, heading.level)
        assertEquals("Title", heading.text)
        assertEquals("body", (blocks[1] as RichBlock.Paragraph).text)
    }

    @Test
    fun splitsHeadingEntities() {
        val text = "Hello\nworld"
        val blocks = splitRichText(text, listOf(TextEntity("heading", 0, 5, "2")))
        val heading = blocks[0] as RichBlock.Heading
        assertEquals(2, heading.level)
        assertEquals("Hello", heading.text)
    }

    @Test
    fun tableKeepsEmptyContinuationCells() {
        val md = """
            | Модель | Объём | Цена |
            | --- | --- | --- |
            | GPT | 200K | $2 |
            | | 500K | $10 |
            | | 1M | $20 |
        """.trimIndent()
        val table = splitRichText(md).single() as RichBlock.Table
        assertEquals(listOf("Модель", "Объём", "Цена"), table.headers)
        assertEquals(
            listOf(
                listOf("GPT", "200K", "$2"),
                listOf("", "500K", "$10"),
                listOf("", "1M", "$20"),
            ),
            table.rows,
        )
    }

    @Test
    fun preTableLanguageBecomesTable() {
        val text = "| Col A | Col B |\n| --- | --- |\n| 1 | 2 |"
        val blocks = splitRichText(text, listOf(TextEntity("pre", 0, text.length, "TABLE")))
        val table = blocks.single() as RichBlock.Table
        assertEquals(listOf("Col A", "Col B"), table.headers)
        assertEquals(listOf(listOf("1", "2")), table.rows)
    }

    @Test
    fun splitsDetailsEntity() {
        val text = "Click to expand\ninner"
        val blocks = splitRichText(
            text,
            listOf(TextEntity("details", 0, text.length)),
        )
        val details = blocks.single() as RichBlock.Details
        assertEquals("Click to expand", details.title)
        assertEquals("inner", (details.children.single() as RichBlock.Paragraph).text)
    }

    @Test
    fun splitsHorizontalRule() {
        val blocks = splitRichText("before\n---\nafter")
        assertEquals(3, blocks.size)
        assertEquals("before", (blocks[0] as RichBlock.Paragraph).text)
        assertEquals(RichBlock.Rule, blocks[1])
        assertEquals("after", (blocks[2] as RichBlock.Paragraph).text)
    }

    @Test
    fun splitsMarkdownQuotes() {
        val md = "> line 1\n> line 2\nregular"
        val blocks = splitRichText(md)
        assertEquals(2, blocks.size)
        assertEquals("line 1\nline 2", (blocks[0] as RichBlock.Quote).text)
        assertEquals("regular", (blocks[1] as RichBlock.Paragraph).text)
    }

    @Test
    fun nestedQuoteMarkersKeepNestedEntities() {
        val quote = splitRichText("> outer\n>> middle\n>>> inner").single() as RichBlock.Quote
        assertEquals("outer\nmiddle\ninner", quote.text)
        assertEquals(1, quote.level)
        assertTrue(quote.entities.any { it.kind == "blockquote" })
    }

    @Test
    fun fullyNestedQuoteMarkersBecomeLevels() {
        val quote = splitRichText(">> two").single() as RichBlock.Quote
        assertEquals("two", quote.text)
        assertEquals(2, quote.level)
        assertTrue(quote.entities.none { it.kind == "blockquote" })
    }

    @Test
    fun markdownTaskListBecomesBlock() {
        val tasks = splitRichText("- [ ] open\n- [x] done").single() as RichBlock.TaskList
        assertEquals(2, tasks.items.size)
        assertEquals("open", tasks.items[0].text)
        assertTrue(!tasks.items[0].done)
        assertEquals("done", tasks.items[1].text)
        assertTrue(tasks.items[1].done)
    }

    @Test
    fun objectReplacementInGapBecomesPhoto() {
        val text = "link\n\uFFFC\nImage title"
        val blocks = splitRichText(text, listOf(TextEntity("heading", 0, 4, "2")))
        assertTrue(blocks.any { it is RichBlock.Photo })
        assertTrue(blocks.none { it is RichBlock.Paragraph && it.text.contains('\uFFFC') })
        assertEquals("Image title", (blocks.last() as RichBlock.Paragraph).text.trim())
    }

    @Test
    fun splitsInlinePhotoEntity() {
        val text = "before\uFFFC\nafter"
        val blocks = splitRichText(
            text,
            listOf(TextEntity("photo", 6, 1, "photo:99:300x200")),
        )
        assertEquals(3, blocks.size)
        assertEquals("before", (blocks[0] as RichBlock.Paragraph).text.trim())
        val photo = blocks[1] as RichBlock.Photo
        assertEquals("photo:99", photo.cacheKey)
        assertEquals(300, photo.width)
        assertEquals(200, photo.height)
        assertEquals("after", (blocks[2] as RichBlock.Paragraph).text.trim())
    }

    @Test
    fun unicodeChecklistLinesBecomeTaskList() {
        val text = "Tasks\n☑ Milk\n☐ Eggs"
        val blocks = splitRichText(text, listOf(TextEntity("heading", 0, 5, "2")))
        assertEquals(2, blocks.size)
        assertEquals("Tasks", (blocks[0] as RichBlock.Heading).text)
        val tasks = blocks[1] as RichBlock.TaskList
        assertEquals("Milk", tasks.items[0].text)
        assertTrue(tasks.items[0].done)
        assertEquals("Eggs", tasks.items[1].text)
        assertTrue(!tasks.items[1].done)
    }

    @Test
    fun keepsInnerBlockquoteEntitiesFromServer() {
        val text = "outer\nmiddle\ninner"
        val blocks = splitRichText(
            text,
            listOf(
                TextEntity("blockquote", 0, text.length),
                TextEntity("blockquote", 6, 6),
                TextEntity("blockquote", 13, 5, url = "collapsed"),
            ),
        )
        val quote = blocks.single() as RichBlock.Quote
        assertEquals(2, quote.entities.count { it.kind == "blockquote" })
        assertTrue(quote.entities.any { it.url == "collapsed" })
    }
}
