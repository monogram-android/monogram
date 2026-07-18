package org.monogram.presentation.features.chats.conversation.ui.inputbar

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.MessageEntityType
import org.monogram.presentation.features.chats.conversation.ui.message.model.entitiesForBlock
import org.monogram.presentation.features.chats.conversation.ui.message.model.topLevelBlockEntities

class EditorRichTextParsingTest {
    @Test
    fun `markdown quote keeps nested table content`() {
        val input = AnnotatedString(
            "> A | B\n> --- | ---\n> 1 | 2\n> 3 | 4"
        )

        val parsed = MarkdownRichTextParser(input).parse()

        assertTrue(parsed.text.contains("┌"))
        assertTrue(parsed.text.contains("1"))
        assertTrue(parsed.text.contains("4"))
        assertFalse(parsed.text.contains(">"))
    }

    @Test
    fun `markdown quote table is extracted as nested table block`() {
        val input = AnnotatedString(
            "> | A | B |\n> | --- | --- |\n> | 1 | 2 |"
        )

        val parsed = MarkdownRichTextParser(input).parse()
        val entities = extractEntities(parsed, emptyMap())

        assertTrue(parsed.text.contains("┌"))
        assertTrue(parsed.text.contains("│ A │ B │"))
        assertEquals(1, entities.count { it.type is MessageEntityType.BlockQuote })
        assertEquals(1, entities.count { (it.type as? MessageEntityType.Pre)?.language == "table" })
    }

    @Test
    fun `markdown quote table keeps table nested under top level quote block`() {
        val input = AnnotatedString(
            "> | A | B |\n> | --- | --- |\n> | 1 | 2 |"
        )

        val parsed = MarkdownRichTextParser(input).parse()
        val entities = extractEntities(parsed, emptyMap())
        val topLevelBlock = entities.topLevelBlockEntities().single()

        assertTrue(topLevelBlock.type is MessageEntityType.BlockQuote)
        assertEquals(
            1,
            entities.entitiesForBlock(topLevelBlock)
                .count { (it.type as? MessageEntityType.Pre)?.language == "table" }
        )
    }
}
