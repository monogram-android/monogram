package org.monogram.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.FixedLinkPreviewRule
import org.monogram.domain.models.FixedLinkPreviewRules

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
    fun `shouldUseFixedPreview recognizes supported services`() {
        assertTrue(resolver.shouldUseFixedPreview("https://x.com/jack/status/20"))
        assertTrue(resolver.shouldUseFixedPreview("https://bsky.app/profile/sandyhorne.bsky.social/post/3mnyfhhx36s25"))
        assertTrue(resolver.shouldUseFixedPreview("https://www.tiktok.com/@mercedesamgf1/video/7650134535434292502"))
        assertTrue(resolver.shouldUseFixedPreview("https://www.reddit.com/r/evilbuildings/comments/1u2q7a8/residential_building_cheloveinik_in_samara_russia"))
        assertTrue(resolver.shouldUseFixedPreview("https://www.pixiv.net/en/artworks/137384764"))
        assertFalse(resolver.shouldUseFixedPreview("https://example.com/post/1"))
        assertFalse(resolver.shouldUseFixedPreview("https://vm.tiktok.com/ZM1234567"))
        assertFalse(resolver.shouldUseFixedPreview("https://www.threads.com/@f1/post/DZcp9gOj9R1"))
        assertFalse(resolver.shouldUseFixedPreview("https://www.instagram.com/p/DZZP4qQkwOQ/"))
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
            "sandyhorne.bsky.social" to "3mnyfhhx36s25",
            resolver.parseBlueskyStatus("https://bsky.app/profile/sandyhorne.bsky.social/post/3mnyfhhx36s25")
        )
        assertNull(resolver.parseBlueskyStatus("https://bsky.app/profile/sandyhorne.bsky.social"))
    }

    @Test
    fun `toFixedPreviewUrl rewrites supported hosts`() {
        assertEquals(
            "https://fxtwitter.com/i/status/123",
            resolver.toFixedPreviewUrl("https://x.com/i/status/123")
        )
        assertEquals(
            "https://fxbsky.app/profile/sandyhorne.bsky.social/post/3mnyvq5ie6k23",
            resolver.toFixedPreviewUrl("https://bsky.app/profile/sandyhorne.bsky.social/post/3mnyvq5ie6k23")
        )
        assertEquals(
            "https://tnktok.com/@mercedesamgf1/video/7650134535434292502?lang=en#sec",
            resolver.toFixedPreviewUrl("https://www.tiktok.com/@mercedesamgf1/video/7650134535434292502?lang=en#sec")
        )
        assertEquals(
            "https://rxddit.com/r/evilbuildings/comments/1u2q7a8/residential_building_cheloveinik_in_samara_russia",
            resolver.toFixedPreviewUrl("https://reddit.com/r/evilbuildings/comments/1u2q7a8/residential_building_cheloveinik_in_samara_russia")
        )
        assertEquals(
            "https://phixiv.net/en/artworks/137384764",
            resolver.toFixedPreviewUrl("https://www.pixiv.net/en/artworks/137384764")
        )
        assertNull(resolver.toFixedPreviewUrl("https://example.com/post/1"))
    }

    @Test
    fun `toFixedPreviewUrls preserves path query fragment and mirror ordering`() {
        assertEquals(
            listOf(
                "https://tnktok.com/@f1/video/7650152004567797014?lang=en#sec",
                "https://tfxktok.com/@f1/video/7650152004567797014?lang=en#sec",
                "https://tiktxk.com/@f1/video/7650152004567797014?lang=en#sec",
                "https://tiktokez.com/@f1/video/7650152004567797014?lang=en#sec"
            ),
            resolver.toFixedPreviewUrls("https://m.tiktok.com/@f1/video/7650152004567797014?lang=en#sec")
        )
        assertEquals(
            listOf(
                "https://rxddit.com/r/evilbuildings/comments/1u2q7a8/residential_building_cheloveinik_in_samara_russia?utm_source=share#comments",
                "https://redditez.com/r/evilbuildings/comments/1u2q7a8/residential_building_cheloveinik_in_samara_russia?utm_source=share#comments"
            ),
            resolver.toFixedPreviewUrls("https://www.reddit.com/r/evilbuildings/comments/1u2q7a8/residential_building_cheloveinik_in_samara_russia?utm_source=share#comments")
        )
    }

    @Test
    fun `fixed link preview rules classify api backed and host rewrite services`() {
        assertTrue(FixedLinkPreviewRules.findRule("https://x.com/jack/status/20") is FixedLinkPreviewRule.ApiBacked)
        assertTrue(FixedLinkPreviewRules.findRule("https://bsky.app/profile/sandyhorne.bsky.social/post/3mnyvq5ie6k23") is FixedLinkPreviewRule.ApiBacked)
        assertTrue(FixedLinkPreviewRules.findRule("https://tiktok.com/@f1/video/7650152004567797014") is FixedLinkPreviewRule.HostRewrite)
        assertTrue(FixedLinkPreviewRules.findRule("https://reddit.com/r/evilbuildings/comments/1u2q7a8/residential_building_cheloveinik_in_samara_russia") is FixedLinkPreviewRule.HostRewrite)
        assertTrue(FixedLinkPreviewRules.findRule("https://pixiv.net/en/artworks/137384764") is FixedLinkPreviewRule.HostRewrite)
        assertNull(FixedLinkPreviewRules.findRule("https://www.threads.com/@kuvshinov_ilya/post/DZZP55Sk7GI"))
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
