package org.monogram.data.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.TelegramPeerChatId

class TelegramPeerChatIdTest {
    @Test
    fun `encodes and decodes a private peer`() {
        val chatId = TelegramPeerChatId.encode(DialogPeerType.PRIVATE, 42L)

        assertEquals(42L, chatId)
        assertEquals(TelegramPeerChatId.Peer(DialogPeerType.PRIVATE, 42L), TelegramPeerChatId.decode(chatId))
    }

    @Test
    fun `encodes and decodes a basic group peer`() {
        val chatId = TelegramPeerChatId.encode(DialogPeerType.BASIC_GROUP, 42L)

        assertEquals(-42L, chatId)
        assertEquals(TelegramPeerChatId.Peer(DialogPeerType.BASIC_GROUP, 42L), TelegramPeerChatId.decode(chatId))
    }

    @Test
    fun `encodes supergroups and channels in the protocol compatible range`() {
        assertEquals(-1_003_768_707_135L, TelegramPeerChatId.encode(DialogPeerType.CHANNEL, 3_768_707_135L))
        assertEquals(-1_003_768_707_135L, TelegramPeerChatId.encode(DialogPeerType.SUPERGROUP, 3_768_707_135L))
        assertEquals(
            TelegramPeerChatId.Peer(DialogPeerType.CHANNEL, 3_768_707_135L),
            TelegramPeerChatId.decode(-1_003_768_707_135L, isChannel = true),
        )
    }

    @Test
    fun `rejects invalid peer identifiers`() {
        assertThrows(IllegalArgumentException::class.java) {
            TelegramPeerChatId.encode(DialogPeerType.PRIVATE, 0L)
        }
        assertThrows(IllegalStateException::class.java) {
            TelegramPeerChatId.encode(DialogPeerType.UNKNOWN, 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TelegramPeerChatId.decode(0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TelegramPeerChatId.decode(-1_000_000_000_001L)
        }
    }
}
