package org.monogram.feature.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.network.http.MediaPriority

class DialogMediaPreloadTest {
    @Test
    fun emptyVisibleFallsBackToNewestPlusRadius() {
        val messages = (50 downTo 1).map { photo(it) }
        val plan = DialogMediaPreload.plan(messages, visibleIds = emptySet(), radius = 20)
        assertEquals(0..0, plan.visibleRange)
        assertEquals(0..20, plan.window)
        assertTrue(plan.media.any { it.message.id.id == 50 && it.priority == MediaPriority.THUMB })
        assertTrue(plan.media.any { it.message.id.id == 30 && it.priority == MediaPriority.IDLE })
        assertFalse(plan.media.any { it.message.id.id == 29 })
    }

    @Test
    fun windowUsesVisiblePlusMinusRadiusAndSkipsOutside() {
        val messages = (50 downTo 1).map { photo(it) }
        val plan = DialogMediaPreload.plan(messages, visibleIds = setOf(25), radius = 20)
        val visibleIndex = messages.indexOfFirst { it.id.id == 25 }
        assertEquals(visibleIndex..visibleIndex, plan.visibleRange)
        assertEquals((visibleIndex - 20)..(visibleIndex + 20), plan.window)
        val ids = plan.media.map { it.message.id.id }.toSet()
        assertTrue(25 in ids)
        assertTrue(5 in ids)
        assertTrue(45 in ids)
        assertFalse(4 in ids)
        assertFalse(46 in ids)
    }

    @Test
    fun visibleOutranksNearbyAndGifGetsFull() {
        val messages = listOf(gif(3), photo(2), photo(1))
        val plan = DialogMediaPreload.plan(messages, visibleIds = setOf(3), radius = 20)
        val visibleGif = plan.media.filter { it.message.id.id == 3 }
        assertTrue(visibleGif.any { it.fetch == DialogMediaPreload.Fetch.Thumb && it.priority == MediaPriority.THUMB })
        assertTrue(visibleGif.any { it.fetch == DialogMediaPreload.Fetch.Full && it.cacheKey == "gif:3" })
        assertTrue(plan.media.filter { it.message.id.id == 1 }.all { it.priority == MediaPriority.IDLE })
        assertTrue(MediaPriority.THUMB > MediaPriority.VISIBLE)
        assertTrue(MediaPriority.VISIBLE > MediaPriority.DEFAULT)
        assertTrue(MediaPriority.DEFAULT > MediaPriority.IDLE)
        assertFalse(plan.media.any { it.message.mediaKind == "document" })
    }

    @Test
    fun webpageCardsAndInstantViewArePlannedWithoutRelatedCrawl() {
        val messages = listOf(
            webpage(3, """{"u":"https://ex/a","iv":true,"h":4}"""),
            webpage(2, """{"u":"https://ex/b","iv":false}"""),
            photo(1),
        )
        val plan = DialogMediaPreload.plan(messages, visibleIds = setOf(3), radius = 20)
        assertTrue(plan.media.any { it.message.id.id == 3 && it.fetch == DialogMediaPreload.Fetch.Thumb })
        assertTrue(
            plan.media.any {
                it.message.id.id == 3 && it.fetch == DialogMediaPreload.Fetch.Display
            },
        )
        assertEquals(listOf("https://ex/a"), plan.instantViews.map { it.url })
        assertEquals(4, plan.instantViews.single().hash)
        assertTrue(plan.instantViews.single().visible)
        assertFalse(plan.instantViews.any { it.url == "https://ex/b" })
    }

    @Test
    fun visibleStickersAndDocumentThumbsArePreloadedWithoutFullDocuments() {
        val messages = listOf(
            Message(
                id = MessageId(PeerId(1), 3),
                senderId = null,
                text = "file.pdf",
                date = 0L,
                outgoing = false,
                mediaKind = "document",
                mediaCacheKey = "doc:3",
                thumbCacheKey = "doc:3:thumb",
            ),
            Message(
                id = MessageId(PeerId(1), 2),
                senderId = null,
                text = "plain.bin",
                date = 0L,
                outgoing = false,
                mediaKind = "document",
                mediaCacheKey = "doc:2",
            ),
            Message(
                id = MessageId(PeerId(1), 1),
                senderId = null,
                text = null,
                date = 0L,
                outgoing = false,
                mediaKind = "sticker",
                mediaCacheKey = "sticker:1",
                thumbCacheKey = "sticker:1:thumb",
            ),
        )
        val plan = DialogMediaPreload.plan(messages, visibleIds = setOf(3, 1), radius = 20)
        assertTrue(plan.media.any {
            it.message.id.id == 3 &&
                it.fetch == DialogMediaPreload.Fetch.Thumb &&
                it.cacheKey == "doc:3:thumb"
        })
        assertFalse(plan.media.any { it.message.id.id == 3 && it.fetch != DialogMediaPreload.Fetch.Thumb })
        assertFalse(plan.media.any { it.message.id.id == 2 })
        assertTrue(plan.media.any {
            it.message.id.id == 1 &&
                it.fetch == DialogMediaPreload.Fetch.Full &&
                it.cacheKey == "sticker:1"
        })
        assertTrue(plan.media.any {
            it.message.id.id == 1 && it.fetch == DialogMediaPreload.Fetch.Thumb
        })
        assertTrue(plan.instantViews.isEmpty())
    }

    private fun photo(id: Int) = Message(
        id = MessageId(PeerId(1), id),
        senderId = null,
        text = null,
        date = id.toLong(),
        outgoing = false,
        mediaKind = "photo",
        mediaCacheKey = "photo:$id",
        thumbCacheKey = "thumb:$id",
    )

    private fun gif(id: Int) = Message(
        id = MessageId(PeerId(1), id),
        senderId = null,
        text = null,
        date = id.toLong(),
        outgoing = false,
        mediaKind = "gif",
        mediaCacheKey = "gif:$id",
        thumbCacheKey = "thumb:$id",
    )

    private fun webpage(id: Int, json: String) = Message(
        id = MessageId(PeerId(1), id),
        senderId = null,
        text = null,
        date = id.toLong(),
        outgoing = false,
        mediaKind = "webpage",
        mediaCacheKey = "web:$id",
        thumbCacheKey = "webthumb:$id",
        fileName = json,
    )
}
