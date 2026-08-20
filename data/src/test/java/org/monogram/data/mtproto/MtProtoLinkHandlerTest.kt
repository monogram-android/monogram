package org.monogram.data.mtproto

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.repository.LinkParser
import org.monogram.domain.repository.LinkAction
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.ResolveUsername
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.ResolvedPeer_28e60b6802
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoLinkHandlerTest {
    @Test
    fun `resolves public username through owned transport`() = runTest {
        val transport = RecordingTransport()
        val handler = MtProtoLinkHandlerImpl(
            parser = LinkParser(),
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = NoOpMtProtoUserProjectionStore,
            chats = NoOpMtProtoChatProjectionStore,
        )

        assertEquals(LinkAction.OpenUser(7), handler.handle("https://t.me/alice"))
        assertEquals("alice", (transport.request as ResolveUsername).username)
        assertTrue(transport.closed)
    }

    private class RecordingTransport : MtProtoRpcTransport {
        lateinit var request: TlMethod<*>
        var closed = false
        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R {
            request = method
            return ResolvedPeer_28e60b6802(PeerUser(7), emptyList(), emptyList()) as R
        }
        override fun close() { closed = true }
    }

    private fun config() = TelegramMtProtoBootstrapConfig(
        endpoint = TelegramMtProtoEndpoint(2, "dc", 443),
        handshake = MtProtoHandshakeConfig(2, listOf("key")),
        cloud = CloudLayer223ConnectionConfig(1, "device", "system", "app", "en"),
    )
}
