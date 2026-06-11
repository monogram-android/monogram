package org.monogram.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftLinkPreviewResolverTest {
    private val resolver = DraftLinkPreviewResolver()

    @Test
    fun `parseTargets deduplicates normalized urls and trims punctuation`() {
        val targets = resolver.parseTargets(
            "Check x.com/foo/status/12345, https://x.com/foo/status/12345 and example.com/path."
        )

        assertEquals(2, targets.size)
        assertEquals("https://x.com/foo/status/12345", targets[0].normalizedUrl)
        assertEquals("https://example.com/path", targets[1].normalizedUrl)
    }

    @Test
    fun `normalizeUrl lowercases host and adds https scheme`() {
        val normalized = resolver.normalizeUrl("WWW.Twitter.com/User/Status/123")

        assertEquals("https://www.twitter.com/User/Status/123", normalized)
    }

    @Test
    fun `shouldUseFixedPreview recognizes twitter and bluesky`() {
        assertTrue(resolver.shouldUseFixedPreview("https://x.com/user/status/123"))
        assertTrue(resolver.shouldUseFixedPreview("https://bsky.app/profile/alice.bsky.social/post/3kxyz"))
        assertFalse(resolver.shouldUseFixedPreview("https://example.com/post/1"))
    }

    @Test
    fun `parseTwitterStatusId extracts numeric status id`() {
        assertEquals(
            "1234567890",
            resolver.parseTwitterStatusId("https://mobile.x.com/user/status/1234567890")
        )
        assertNull(resolver.parseTwitterStatusId("https://x.com/user/status/not-a-number"))
    }

    @Test
    fun `parseBlueskyStatus extracts handle and rkey`() {
        assertEquals(
            "alice.bsky.social" to "3kxyz",
            resolver.parseBlueskyStatus("https://bsky.app/profile/alice.bsky.social/post/3kxyz")
        )
        assertNull(resolver.parseBlueskyStatus("https://bsky.app/profile/alice.bsky.social"))
    }
}
