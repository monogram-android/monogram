package org.monogram.feature.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemEmojiCatalogTest {
    @Test
    fun keycapsCoverDigitsHashAndStar() {
        val glyphs = SystemEmojiCatalog.keycapGlyphs()
        assertEquals(12, glyphs.size)
        assertTrue(glyphs.contains("0\uFE0F\u20E3"))
        assertTrue(glyphs.contains("9\uFE0F\u20E3"))
        assertTrue(glyphs.contains("#\uFE0F\u20E3"))
        assertTrue(glyphs.contains("*\uFE0F\u20E3"))
        assertEquals(glyphs.size, glyphs.toSet().size)
    }

    @Test
    fun skinToneScaleHasFiveModifiers() {
        assertEquals(5, SystemEmojiCatalog.skinToneCount())
    }
}
