package org.monogram.feature.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.feature.dialog.store.matchingPending
import java.io.File

class OutgoingMediaTest {
    @Test
    fun localMediaKeyRoundTripsPath() {
        val path = "/data/cache/attach-1.jpg"
        assertEquals(path, localMediaPath(localMediaCacheKey(path)))
        assertNull(localMediaPath("photo:1:2"))
        assertNull(localMediaPath(null))
    }

    @Test
    fun parseUpdateMessageIdReadsRandomAndServerId() {
        assertEquals(99L to 44, parseUpdateMessageId("UpdateMessageId:99:44"))
        assertNull(parseUpdateMessageId("UpdateMessageId"))
        assertNull(parseUpdateMessageId("UpdateMessageId:0:12"))
        assertNull(parseUpdateMessageId("UpdateMessageId:9:0"))
        assertNull(parseUpdateMessageId("difference_too_long"))
    }

    @Test
    fun timeoutIsTransientSendFailure() {
        val timeout = TelegramError.parse("rpc timeout")
        assertTrue(isTransientSendFailure(timeout))
        assertFalse(isTransientSendFailure(TelegramError.parse("RPC 400: MEDIA_EMPTY")))
    }

    @Test
    fun matchingPendingUsesOldestSameKind() {
        val chat = PeerId(5)
        val older = Message(
            id = MessageId(chat, -2),
            senderId = null,
            text = null,
            date = 1L,
            outgoing = true,
            mediaKind = "photo",
            pending = true,
            randomId = 1L,
        )
        val newer = older.copy(id = MessageId(chat, -1), randomId = 2L)
        val incoming = older.copy(
            id = MessageId(chat, 44),
            pending = false,
            randomId = null,
        )
        val matched = matchingPending(listOf(newer, older), incoming)
        assertEquals(-2, matched?.id?.id)
    }

    @Test
    fun localOutgoingFileRequiresExistingPath() {
        val missing = localOutgoingFile(localMediaCacheKey("/no/such/file.jpg"))
        assertNull(missing)
        val file = File.createTempFile("outgoing", ".jpg").apply { writeBytes(byteArrayOf(1)) }
        assertEquals(file.absolutePath, localOutgoingFile(localMediaCacheKey(file.absolutePath))?.absolutePath)
    }
}
