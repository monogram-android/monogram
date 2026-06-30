package org.monogram.presentation.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class UriTempFileUtilsTest {
    @Test
    fun `resolveMediaExtension keeps heic for heic images`() {
        assertEquals("heic", resolveMediaExtension("image/heic"))
    }

    @Test
    fun `resolveMediaExtension keeps png for png images`() {
        assertEquals("png", resolveMediaExtension("image/png"))
    }

    @Test
    fun `resolveMediaExtension keeps webp for webp images`() {
        assertEquals("webp", resolveMediaExtension("image/webp"))
    }

    @Test
    fun `resolveMediaExtension falls back to jpg for unknown image mime`() {
        assertEquals("jpg", resolveMediaExtension("image/unknown"))
    }
}
