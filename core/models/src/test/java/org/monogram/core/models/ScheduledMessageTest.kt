package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScheduledMessageTest {
    private fun queued(id: Int) = ScheduledMessage(
        Message(
            id = MessageId(PeerId(7), id), senderId = null, text = "caption",
            date = 0, outgoing = true,
        )
    )

    @Test
    fun editingRequiresKnownContentAndACaptionCapableType() {
        assertEquals(false, queued(1).canEditText)
        for (kind in listOf(null, "photo", "video", "gif", "document", "audio")) {
            val known = queued(1).copy(
                message = queued(1).message.copy(mediaKind = kind),
                hasUnsupportedContent = false
            )
            assertEquals(true, known.canEditText)
            assertEquals(false, known.copy(hasUnsupportedContent = true).canEditText)
        }
        for (kind in listOf("poll", "sticker", "voice", "video_note", "service", "unsupported")) {
            assertEquals(
                false,
                queued(1).copy(
                    message = queued(1).message.copy(mediaKind = kind),
                    hasUnsupportedContent = false
                ).canEditText
            )
        }
    }

    @Test
    fun mediaIdsMatchNativeNamespaceWithoutChangingQueueIdentity() {
        for ((id, mediaId) in listOf(1 to -2, 37 to -38, Int.MAX_VALUE to Int.MIN_VALUE)) {
            val item = queued(id)
            assertEquals(MessageId(PeerId(7), mediaId), item.mediaMessageId)
            assertEquals(id, item.message.id.id)
        }
        assertThrows(IllegalArgumentException::class.java) { queued(0).mediaMessageId }
    }
}
