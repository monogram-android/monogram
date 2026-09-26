package org.monogram.feature.dialog.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.monogram.core.models.RichBlock
import org.monogram.core.models.TextEntity

class DialogRenderCacheBlocksTest {
    @Test
    fun recycleReusesParsedBlocks() {
        val key = DialogRenderCache.blocksKey("**hi**", emptyList(), true)
        assertNull(DialogRenderCache.blocks(key))
        val parsed = listOf(RichBlock.Paragraph("hi", emptyList()))
        DialogRenderCache.putBlocks(key, parsed)
        assertSame(parsed, DialogRenderCache.blocks(key))
        // Same logical message reuses the entry instead of re-parsing.
        assertEquals(key, DialogRenderCache.blocksKey("**hi**", emptyList(), true))
    }

    @Test
    fun keySeparatesTextEntitiesAndMode() {
        val plain = DialogRenderCache.blocksKey("hello", emptyList(), true)
        val other = DialogRenderCache.blocksKey("hello!", emptyList(), true)
        val raw = DialogRenderCache.blocksKey("hello", emptyList(), false)
        val entity = DialogRenderCache.blocksKey(
            "hello",
            listOf(TextEntity("bold", 0, 5, null)),
            true,
        )
        assertNotEquals(plain, other)
        assertNotEquals(plain, raw)
        assertNotEquals(plain, entity)
    }
}
