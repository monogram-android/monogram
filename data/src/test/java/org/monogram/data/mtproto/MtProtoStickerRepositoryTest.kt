package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.InputStickerSetShortName
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
    fun `uses short name input and closes selected transport`() = runBlocking {
        val transport = Transport()
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

    private class Transport : MtProtoRpcTransport {
        lateinit var request: TlMethod<*>
        var closed = false
        override suspend fun <R> execute(method: TlMethod<R>): R {
            request = method
            @Suppress("UNCHECKED_CAST")
            return null as R
        }
        override fun close() { closed = true }
    }
}
