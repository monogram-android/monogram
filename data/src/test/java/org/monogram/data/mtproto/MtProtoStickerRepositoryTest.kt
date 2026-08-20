package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.InputStickerSetShortName
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.ClearRecentStickers
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetRecentStickers
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.RecentStickers_ee91009b24
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetStickerSet
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoStickerRepositoryTest {
    @Test
    fun `rejects ID-only sticker reads before opening transport`() = runBlocking {
        var opened = false
        val repository = repository { opened = true; Transport() }

        assertThrows(UnsupportedOperationException::class.java) {
            runBlocking { repository.getStickerSet(42L) }
        }
        assertEquals(false, opened)
    }

    @Test
    fun `rejects blank sticker set name before opening transport`() = runBlocking {
        var opened = false
        val repository = repository { opened = true; Transport() }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.getStickerSetByName("  ") }
        }
        assertEquals(false, opened)
    }

    @Test
    fun `reads and clears standard recent stickers through selected transport`() = runBlocking {
        val transport = Transport(RecentStickers_ee91009b24(0, emptyList(), emptyList(), emptyList()), true)
        val repository = repository { transport }

        assertEquals(emptyList<Any>(), repository.getRecentStickers())
        repository.clearRecentStickers()

        assertEquals(GetRecentStickers(attached = false, hash = 0), transport.requests[0])
        assertEquals(ClearRecentStickers(attached = false), transport.requests[1])
        assertEquals(true, transport.closed)
    }

    @Test
    fun `uses short name input and closes selected transport`() = runBlocking {
        val transport = Transport(null)
        val repository = repository { transport }

        assertEquals(null, repository.getStickerSetByName("monogram"))
        val request = transport.request as GetStickerSet
        assertEquals("monogram", (request.stickerset as InputStickerSetShortName).shortName)
        assertEquals(true, transport.closed)
    }

    private fun repository(factory: () -> MtProtoRpcTransport) = MtProtoStickerRepository(
        configSource = object : TelegramMtProtoBootstrapConfigSource {
            override suspend fun create() = TelegramMtProtoBootstrapConfig(
                TelegramMtProtoEndpoint(2, "dc", 443),
                MtProtoHandshakeConfig(2, listOf("key")),
                CloudLayer223ConnectionConfig(1, "d", "s", "a", "en"),
            )
        },
        transportFactory = MtProtoSessionTransportFactory { factory() },
    )

    private class Transport(vararg responses: Any?) : MtProtoRpcTransport {
        private val responses = responses.toList()
        val requests = mutableListOf<TlMethod<*>>()
        val request: TlMethod<*> get() = requests.single()
        var closed = false
        override suspend fun <R> execute(method: TlMethod<R>): R {
            requests += method
            @Suppress("UNCHECKED_CAST")
            return responses[requests.lastIndex] as R
        }
        override fun close() { closed = true }
    }
}
