package org.monogram.feature.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineBotQueryTest {
    @Test
    fun inlineQueryAtMessageStartDoesNotNeedSpace() {
        assertEquals(InlineBotQuery("gif", ""), InlineBotQueries.parse("@gif"))
        assertEquals(InlineBotQuery("gif", ""), InlineBotQueries.parse("@gif "))
        assertEquals(InlineBotQuery("gif", "cats"), InlineBotQueries.parse("@gif cats"))
        assertEquals(InlineBotQuery("pic", "sunset"), InlineBotQueries.parse("  @pic sunset"))
        assertEquals(InlineBotQuery("vid", "q"), InlineBotQueries.parse("@Vid q"))
        assertNull(InlineBotQueries.parse("hello @gif cats"))
        assertNull(InlineBotQueries.parse("@ab q"))
        assertNull(InlineBotQueries.parse("@"))
    }

    @Test
    fun mentionTokenStaysOpenUntilSpace() {
        assertEquals("", ComposerAt.mentionToken("@")?.handle)
        assertEquals("user", ComposerAt.mentionToken("@user")?.handle)
        assertFalse(ComposerAt.mentionToken("@user")!!.hasSpace)
        assertTrue(ComposerAt.mentionToken("@user")!!.atMessageStart)
        val spaced = ComposerAt.mentionToken("@user query")!!
        assertEquals("user", spaced.handle)
        assertEquals("query", spaced.rest)
        assertTrue(spaced.hasSpace)
        assertEquals(
            InlineBotQuery("user", ""),
            ComposerAt.inlineQuery(ComposerAt.mentionToken("@user")!!),
        )
        assertEquals(
            InlineBotQuery("user", "query"),
            ComposerAt.inlineQuery(ComposerAt.mentionToken("@user query")!!),
        )
        assertNull(ComposerAt.inlineQuery(ComposerAt.mentionToken("hello @user query")!!))
    }

    @Test
    fun replaceHandleKeepsSurroundingText() {
        val token = ComposerAt.mentionToken("hi @ad")!!
        assertEquals("hi @ada ", ComposerAt.replaceHandle("hi @ad", token, "@ada "))
    }
}
