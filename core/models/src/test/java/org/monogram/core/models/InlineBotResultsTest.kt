package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineBotResultsTest {
    @Test
    fun galleryGifResultKeepsIdAndDocument() {
        val page = InlineBotResults(
            queryId = 9L,
            gallery = true,
            nextOffset = "abc",
            cacheTime = 30,
            results = listOf(
                InlineBotResult(
                    id = "gif-1",
                    kind = "gif",
                    documentId = 42L,
                    thumbCacheKey = "emoji:42",
                ),
            ),
        )
        assertTrue(page.gallery)
        assertEquals("gif-1", page.results.single().id)
        assertEquals(42L, page.results.single().documentId)
        assertEquals("abc", page.nextOffset)
        assertEquals(42L, page.results.single().previewLookupId())
    }

    @Test
    fun photoGalleryResultUsesPhotoCacheKeyAsLookup() {
        val item = InlineBotResult(
            id = "p1",
            kind = "photo",
            thumbCacheKey = "photo:88",
        )
        assertEquals(88L, item.previewLookupId())
        assertEquals(
            null,
            InlineBotResult(id = "x", kind = "article").previewLookupId(),
        )
    }

    @Test
    fun resolvedBotKeepsUsername() {
        val peer = ResolvedPeer(
            peerId = PeerId(7),
            username = "gif",
            title = "GIF Bot",
            isBot = true,
        )
        assertTrue(peer.isBot)
        assertEquals("gif", peer.username)
    }
}
