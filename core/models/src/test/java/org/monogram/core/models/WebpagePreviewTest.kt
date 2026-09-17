package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebpagePreviewTest {
    @Test
    fun parseKeepsUrlTitleAndSite() {
        val parsed = WebpagePreviews.parse(
            """{"u":"https://youtu.be/abc","t":"Cat","s":"YouTube","d":"A clip","e":null,"y":"video"}""",
        )
        requireNotNull(parsed)
        assertEquals("https://youtu.be/abc", parsed.url)
        assertEquals("Cat", parsed.title)
        assertEquals("YouTube", parsed.siteName)
        assertEquals("A clip", parsed.description)
        assertEquals("video", parsed.type)
        assertFalse(parsed.offersInstantView)
    }

    @Test
    fun parseKeepsInstantViewFlagAndHash() {
        val parsed = WebpagePreviews.parse(
            """{"u":"https://telegra.ph/a","t":"Note","y":"article","iv":true,"h":12}""",
        )
        requireNotNull(parsed)
        assertTrue(parsed.offersInstantView)
        assertEquals(12, parsed.hash)
    }

    @Test
    fun telegramAlbumDoesNotOfferInstantView() {
        val parsed = WebpagePreviews.parse(
            """{"u":"https://t.me/c/1/2","y":"telegram_album","iv":true,"h":1}""",
        )
        requireNotNull(parsed)
        assertFalse(parsed.offersInstantView)
    }

    @Test
    fun parseRejectsMissingUrl() {
        assertNull(WebpagePreviews.parse("""{"t":"Nope"}"""))
        assertNull(WebpagePreviews.parse("plain title"))
        assertNull(WebpagePreviews.parse(null))
    }

    @Test
    fun photoTypeWithCachedPageOffersInstantView() {
        val parsed = WebpagePreviews.parse(
            """{"u":"https://x.com/a/status/1","t":"Post","y":"photo","iv":true,"h":4}""",
        )
        requireNotNull(parsed)
        assertTrue(parsed.offersInstantView)
        assertEquals(4, parsed.hash)
    }

    @Test
    fun instantViewFlagAcceptsNumericTrue() {
        val parsed = WebpagePreviews.parse(
            """{"u":"https://example.com/a","y":"article","iv":1}""",
        )
        requireNotNull(parsed)
        assertTrue(parsed.offersInstantView)
    }
}
