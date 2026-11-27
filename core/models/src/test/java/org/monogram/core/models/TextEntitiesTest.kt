package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEntitiesTest {
    @Test
    fun parseAndSerializeRoundTrip() {
        val source = listOf(
            TextEntity("bold", 0, 4),
            TextEntity("text_url", 5, 3, "https://t.me"),
        )
        val json = TextEntities.serialize(source)
        assertTrue(json!!.contains("\"kind\":\"bold\""))
        val parsed = TextEntities.parse(json)
        assertEquals(2, parsed.size)
        assertEquals("bold", parsed[0].kind)
        assertEquals(0, parsed[0].offset)
        assertEquals(4, parsed[0].length)
        assertEquals("https://t.me", parsed[1].url)
    }

    @Test
    fun entityMetadataEscapesAllJsonControlCharacters() {
        val entity = TextEntity("text_url", 0, 1, "https://example.org/\t\r\n\b\u0000\u001f\"\\")
        val encoded = TextEntities.serialize(listOf(entity))!!
        assertTrue(encoded.none { it.code < 0x20 })
        assertEquals(listOf(entity), TextEntities.parse(encoded))
    }

    @Test
    fun proofreadReplacementsPreserveOriginalTextMetadata() {
        val json = """[{"kind":"diff_replace","offset":3,"length":5,"text":"old\ntext"}]"""
        val parsed = TextEntities.parse(json)
        assertEquals("old\ntext", parsed.single().oldText)
        assertEquals(parsed, TextEntities.parse(TextEntities.serialize(parsed)))
    }

    @Test
    fun objectPatternCompilesOnIcuSafeBraces() {
        val parsed = TextEntities.parse("""[{"kind":"italic","offset":1,"length":2}]""")
        assertEquals(1, parsed.size)
        assertEquals("italic", parsed[0].kind)
        assertEquals(1, parsed[0].offset)
        assertEquals(2, parsed[0].length)
    }

    @Test
    fun parsesMarkdownHeadings() {
        val text = "# Title\nbody"
        val entities = parseInlineMarkdown(text)
        assertEquals("bold", entities.single().kind)
        assertEquals(
            "Title", text.substring(entities[0].offset, entities[0].offset + entities[0].length)
        )
    }

    @Test
    fun parsesSpoilersAndMergesWhenMissing() {
        val text = "hide ||secret|| now"
        val spoilers = parseInlineMarkdown(text).filter { it.kind == "spoiler" }
        assertEquals(
            "secret", text.substring(
                spoilers.single().offset, spoilers.single().offset + spoilers.single().length
            )
        )
    }

    @Test
    fun parsesInlineMarkdown() {
        val text = "**bold** and *italic* and `code`"
        val entities = parseInlineMarkdown(text)
        assertEquals("bold", entities[0].kind)
        assertEquals(
            "bold", text.substring(entities[0].offset, entities[0].offset + entities[0].length)
        )
        assertEquals("italic", entities[1].kind)
        assertEquals(
            "italic", text.substring(entities[1].offset, entities[1].offset + entities[1].length)
        )
        assertEquals("code", entities[2].kind)
    }

    @Test
    fun emptyAndInvalid() {
        assertTrue(TextEntities.parse(null).isEmpty())
        assertTrue(TextEntities.parse("").isEmpty())
        assertTrue(TextEntities.parse("not-json").isEmpty())
        assertEquals(null, TextEntities.serialize(emptyList()))
    }
}
