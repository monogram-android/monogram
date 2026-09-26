package org.monogram.core.common.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.NotifyException
import org.monogram.core.models.NotifySettings
import org.monogram.core.models.PeerId

class NotifyExceptionCodecTest {
    @Test
    fun roundTripsAPeerException() {
        val item = NotifyException(
            peerKind = "user",
            chatId = PeerId(42),
            settings = NotifySettings(muteUntil = Int.MAX_VALUE, silent = true, sound = "soft"),
        )
        assertEquals(item, NotifyExceptionCodec.decode(NotifyExceptionCodec.encode(item)))
    }

    @Test
    fun roundTripsAListAndSkipsJunk() {
        val items = listOf(
            NotifyException("chat", PeerId(1), NotifySettings(muteUntil = 9)),
            NotifyException("channel", PeerId(2), NotifySettings()),
        )
        val encoded = NotifyExceptionCodec.encodeAll(items) + "\nnot-a-row"
        assertEquals(items, NotifyExceptionCodec.decodeAll(encoded))
        assertTrue(NotifyExceptionCodec.decodeAll(null).isEmpty())
    }
}
