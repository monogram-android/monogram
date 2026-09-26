package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadItemTest {
    @Test
    fun albumItemKeepsRandomIdAndKind() {
        val item = UploadItem(
            path = "/tmp/a.jpg",
            kind = "photo",
            fileName = "a.jpg",
            randomId = 99L,
        )
        assertEquals("photo", item.kind)
        assertEquals(99L, item.randomId)
        assertTrue(item.caption.isEmpty())
    }

    @Test
    fun pendingMessageStoresRandomId() {
        val message = Message(
            id = MessageId(PeerId(1), -3),
            senderId = PeerId(1),
            text = null,
            date = 1L,
            outgoing = true,
            pending = true,
            randomId = 7L,
            mediaKind = "video",
        )
        assertTrue(message.pending)
        assertEquals(7L, message.randomId)
        assertNotEquals(0, message.id.id)
    }
}
