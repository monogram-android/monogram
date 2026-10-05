package org.monogram.feature.dialog.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.core.models.TextEntity

class MessageMediaViewerSaverTest {
    private val scope = SaverScope { value -> isSaveable(value) }

    @Test
    fun selectedMessageAndAlbumUseTheSameDescriptorAndPreserveViewerMetadata() {
        val message = Message(
            id = MessageId(PeerId(-100L), 42),
            senderId = PeerId(25L),
            text = "caption with a link",
            date = 1_700_000_000L,
            outgoing = true,
            mediaKind = "video",
            mediaCacheKey = "media",
            thumbCacheKey = "thumb",
            mediaDuration = 75,
            mediaWidth = 1920,
            mediaHeight = 1080,
            groupedId = 99L,
            fileName = "clip.mp4",
            fileSize = 123456L,
            supportsStreaming = true,
            noforwards = true,
            senderName = "Sender",
            reactionsJson = "[{\"emoji\":\"👍\",\"count\":2}]",
            entities = listOf(
                TextEntity("bold", 0, 7),
                TextEntity("text_url", 15, 4, "https://example.com"),
            ),
        )
        val selected = with(MessageSaver) { scope.save(message) }!!
        val album = with(AlbumSaver) { scope.save(listOf(message)) }!!

        assertEquals(album, selected)
        assertEquals(message, MessageSaver.restore(selected))
        assertEquals(listOf(message), AlbumSaver.restore(album))
        assertTrue(isSaveable(selected))
    }

    @Test
    fun albumPreservesOrderAndNullableOrEmptyDescriptorValues() {
        val first = Message(
            id = MessageId(PeerId(1L), 3),
            senderId = null,
            text = "",
            date = 0L,
            outgoing = false,
            mediaKind = "photo",
            fileSize = 0L,
            mediaDuration = 0,
        )
        val second = first.copy(
            id = MessageId(PeerId(1L), 4),
            text = null,
            mediaKind = null,
            fileSize = null,
            mediaDuration = null,
        )
        val messages = listOf(first, second)
        val saved = with(AlbumSaver) { scope.save(messages) }!!

        assertEquals(messages, AlbumSaver.restore(saved))
        assertEquals(first, MessageSaver.restore(with(MessageSaver) { scope.save(first) }!!))
        assertNull(with(MessageSaver) { scope.save(null) })
        assertNull(MessageSaver.restore(arrayListOf<Any>()))
        assertEquals(emptyList<Message>(), AlbumSaver.restore(with(AlbumSaver) { scope.save(emptyList()) }!!))
    }

    @Test
    fun restoresLegacySevenFieldSelectedMessage() {
        val saved = arrayListOf<Any>(-100L, 42, "photo", "media", "", "caption", -1L)

        assertEquals(
            Message(
                id = MessageId(PeerId(-100L), 42),
                senderId = null,
                text = "caption",
                date = 0L,
                outgoing = false,
                mediaKind = "photo",
                mediaCacheKey = "media",
            ),
            MessageSaver.restore(saved),
        )
    }

    @Test
    fun restoresEveryLegacyTwelveFieldAlbumRecord() {
        val first = arrayListOf<Any>(-100L, 42, "video", "media", "thumb", "caption", 123L,
            75, 1920, 1080, 1_700_000_000L, 1L)
        val second = arrayListOf<Any>(-100L, 43, "photo", "other", "", "", -1L,
            -1, -1, -1, 1_700_000_001L, 0L)
        val messages = AlbumSaver.restore(ArrayList(first + second))!!

        assertEquals(listOf(42, 43), messages.map { it.id.id })
        assertEquals(75, messages[0].mediaDuration)
        assertEquals(1920, messages[0].mediaWidth)
        assertEquals(1080, messages[0].mediaHeight)
        assertEquals(123L, messages[0].fileSize)
        assertEquals(1_700_000_000L, messages[0].date)
        assertTrue(messages[0].noforwards)
        assertNull(messages[1].mediaDuration)
        assertNull(messages[1].fileSize)
        assertNull(messages[1].text)
    }

    private fun isSaveable(value: Any?): Boolean = when (value) {
        null, is String, is Int, is Long, is Boolean -> true
        is ArrayList<*> -> value.all(::isSaveable)
        else -> false
    }
}
