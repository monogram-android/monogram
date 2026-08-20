package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.InputReportReasonSpam
import org.monogram.mtproto.tl.generated.cloud.layer223.account.ReportPeer
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoReportPeerRepositoryTest {
    @Test
    fun `reports projected peer with typed reason`() = runBlocking {
        val transport = RecordingTransport()
        val repository = MtProtoReportPeerRepositoryImpl(
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = FakeUserStore(),
            chats = NoOpMtProtoChatProjectionStore,
        )

        repository.report(setOf(42L), "spam", emptyList())

        val request = transport.requests.single() as ReportPeer
        assertEquals(InputPeerUser(42L, 99L), request.peer)
        assertEquals(InputReportReasonSpam, request.reason)
        assertEquals("", request.message)
        assertTrue(transport.closed)
    }

    @Test
    fun `rejects message report before opening transport`() {
        val transport = RecordingTransport()
        val repository = MtProtoReportPeerRepositoryImpl(
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = FakeUserStore(),
            chats = NoOpMtProtoChatProjectionStore,
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.report(setOf(42L), "spam", listOf(7L)) }
        }
        assertEquals(emptyList<TlMethod<*>>(), transport.requests)
    }

    private fun config() = TelegramMtProtoBootstrapConfig(
        endpoint = TelegramMtProtoEndpoint(2, "dc", 443),
        handshake = MtProtoHandshakeConfig(2, listOf("key")),
        cloud = CloudLayer223ConnectionConfig(1, "device", "system", "app", "en"),
    )

    private class RecordingTransport : MtProtoRpcTransport {
        val requests = mutableListOf<TlMethod<*>>()
        var closed = false
        override suspend fun <R> execute(method: TlMethod<R>): R {
            requests += method
            @Suppress("UNCHECKED_CAST")
            return true as R
        }
        override fun close() { closed = true }
    }

    private class FakeUserStore : MtProtoUserProjectionStore by NoOpMtProtoUserProjectionStore {
        override suspend fun get(scope: MtProtoAuthKeyScope, userId: Long) = MtProtoUserReadModel(
            userId, 99L, "User", null, null, null, false, false, false, false, false, false, false, false, false, false, false,
        )
    }
}
