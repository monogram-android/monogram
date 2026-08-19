package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.DialogPeerType
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.AffectedMessages_49c522afbd
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.ReadHistory
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoReadHistoryRepositoryImplTest {
    @Test
    fun `marks encoded basic-group history read through owned transport`() = runBlocking {
        val transport = RecordingTransport()
        val repository = MtProtoReadHistoryRepositoryImpl(
            configSource = configSource(),
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = NoOpMtProtoUserProjectionStore,
            chats = NoOpMtProtoChatProjectionStore,
        )

        repository.markRead(-7L, DialogPeerType.BASIC_GROUP, 42L)

        val request = transport.method as ReadHistory
        assertEquals(InputPeerChat(7L), request.peer)
        assertEquals(42, request.maxId)
        assertTrue(transport.closed)
    }

    private fun configSource() = TelegramMtProtoBootstrapConfigSource {
        TelegramMtProtoBootstrapConfig(
            endpoint = TelegramMtProtoEndpoint(2, "dc", 443),
            handshake = MtProtoHandshakeConfig(2, listOf("key")),
            cloud = CloudLayer223ConnectionConfig(1, "test", "test", "test", "en"),
        )
    }

    private class RecordingTransport : MtProtoRpcTransport {
        lateinit var method: TlMethod<*>
        var closed = false

        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R {
            this.method = method
            return AffectedMessages_49c522afbd(0, 0) as R
        }

        override fun close() { closed = true }
    }
}
