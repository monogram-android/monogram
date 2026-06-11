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

    @Test
    fun `toFixedPreviewUrl rewrites supported hosts`() {
        assertEquals(
            "https://fxtwitter.com/i/status/123",
            resolver.toFixedPreviewUrl("https://x.com/i/status/123")
        )
        assertEquals(
            "https://fxbsky.app/profile/alice.bsky.social/post/3kxyz",
            resolver.toFixedPreviewUrl("https://bsky.app/profile/alice.bsky.social/post/3kxyz")
        )
        assertNull(resolver.toFixedPreviewUrl("https://example.com/post/1"))
    }

    @Test
    fun `twitter photo url still resolves status id and fixed host`() {
        val twitterPhotoUrl = "https://x.com/jack/status/1061124990244929536/photo/1"

        assertTrue(resolver.shouldUseFixedPreview(twitterPhotoUrl))
        assertEquals("1061124990244929536", resolver.parseTwitterStatusId(twitterPhotoUrl))
        assertEquals(
            "https://fxtwitter.com/jack/status/1061124990244929536/photo/1",
            resolver.toFixedPreviewUrl(twitterPhotoUrl)
        )
    }

    @Test
    fun `known twitter and bluesky urls from manual testing are supported`() {
        val twitterTextUrl = "https://x.com/jack/status/20"
        val blueskyImageUrl = "https://bsky.app/profile/sandyhorne.bsky.social/post/3mnyvq5ie6k23"
        val blueskyTextUrl = "https://bsky.app/profile/sandyhorne.bsky.social/post/3mnyfhhx36s25"

        assertTrue(resolver.shouldUseFixedPreview(twitterTextUrl))
        assertEquals("20", resolver.parseTwitterStatusId(twitterTextUrl))
        assertEquals("https://fxtwitter.com/jack/status/20", resolver.toFixedPreviewUrl(twitterTextUrl))

        assertTrue(resolver.shouldUseFixedPreview(blueskyImageUrl))
        assertEquals(
            "sandyhorne.bsky.social" to "3mnyvq5ie6k23",
            resolver.parseBlueskyStatus(blueskyImageUrl)
        )
        assertEquals(
            "https://fxbsky.app/profile/sandyhorne.bsky.social/post/3mnyvq5ie6k23",
            resolver.toFixedPreviewUrl(blueskyImageUrl)
        )

        assertTrue(resolver.shouldUseFixedPreview(blueskyTextUrl))
        assertEquals(
            "sandyhorne.bsky.social" to "3mnyfhhx36s25",
            resolver.parseBlueskyStatus(blueskyTextUrl)
        )
        assertEquals(
            "https://fxbsky.app/profile/sandyhorne.bsky.social/post/3mnyfhhx36s25",
            resolver.toFixedPreviewUrl(blueskyTextUrl)
        )
    }
}
