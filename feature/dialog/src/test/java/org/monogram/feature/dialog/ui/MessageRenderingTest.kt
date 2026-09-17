package org.monogram.feature.dialog.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageRenderingTest {
    @Test
    fun entityHrefMapsOfficialKinds() {
        assertEquals("https://t.me/a", entityHref("text_url", "https://t.me/a", "link"))
        assertEquals("https://example.com", entityHref("url", null, "https://example.com"))
        assertEquals("mailto:a@b.c", entityHref("email", null, "a@b.c"))
        assertEquals("tel:+15551212", entityHref("phone", null, "+1 555 1212"))
        assertEquals("https://t.me/ada", entityHref("mention", null, "@ada"))
        assertEquals("tg://user?id=42", entityHref("mention_name", "42", "Ada"))
        assertEquals("tg://user?id=42", entityHref("text_mention", "42", "Ada"))
        assertNull(entityHref("hashtag", null, "#tag"))
        assertNull(entityHref("bold", null, "x"))
    }
}
