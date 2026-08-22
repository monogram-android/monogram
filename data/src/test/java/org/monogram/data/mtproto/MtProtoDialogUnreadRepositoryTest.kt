package org.monogram.data.mtproto

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.InputDialogPeer_5b57e298d7
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.MarkDialogUnread
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoDialogUnreadRepositoryTest {
    @Test
    fun `marks resolved peer unread and persists it after acknowledgement`() = runTest {
        val transport = RecordingTransport()
        val store = RecordingDialogStore()
        val repository = repository(transport, store)

        repository.setUnread(setOf(42L), unread = true)

        val request = transport.requests.single() as MarkDialogUnread
        assertTrue(request.unread)
        assertEquals(InputPeerUser(42L, 99L), (request.peer as InputDialogPeer_5b57e298d7).peer)
        assertEquals(listOf(Triple(MtProtoMessagePeerType.USER, 42L, true)), store.updates)
        assertTrue(transport.closed)
    }

    @Test
    fun `does not persist unread mark when rpc fails`() = runTest {
        val transport = RecordingTransport(fail = true)
        val store = RecordingDialogStore()
        val repository = repository(transport, store)

        runCatching { repository.setUnread(setOf(42L), unread = true) }
            .onSuccess { error("Expected RPC failure") }

        assertTrue(store.updates.isEmpty())
        assertTrue(transport.closed)
    }

    private fun repository(transport: RecordingTransport, store: RecordingDialogStore) =
        MtProtoDialogUnreadRepositoryImpl(
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = FakeUserStore(),
            chats = NoOpMtProtoChatProjectionStore,
            dialogStore = store,
        )

    private fun config() = TelegramMtProtoBootstrapConfig(
        endpoint = TelegramMtProtoEndpoint(2, "dc", 443),
        handshake = MtProtoHandshakeConfig(2, listOf("key")),
        cloud = CloudLayer223ConnectionConfig(1, "device", "system", "app", "en"),
    )

    private class RecordingTransport(private val fail: Boolean = false) : MtProtoRpcTransport {
        val requests = mutableListOf<TlMethod<*>>()
        var closed = false

        override suspend fun <R> execute(method: TlMethod<R>): R {
            requests += method
            check(!fail) { "RPC failed" }
            @Suppress("UNCHECKED_CAST")
            return true as R
        }

        override fun close() { closed = true }
    }

    private class RecordingDialogStore : MtProtoDialogStore by NoOpMtProtoDialogStore {
        val updates = mutableListOf<Triple<MtProtoMessagePeerType, Long, Boolean>>()
        override suspend fun setUnreadMark(scope: MtProtoAuthKeyScope, peerType: MtProtoMessagePeerType, peerId: Long, unread: Boolean) {
            updates += Triple(peerType, peerId, unread)
        }
    }

    private class FakeUserStore : MtProtoUserProjectionStore by NoOpMtProtoUserProjectionStore {
        override suspend fun get(scope: MtProtoAuthKeyScope, userId: Long) = MtProtoUserReadModel(
            userId, 99L, "User", null, null, null, false, false, false, false, false, false, false, false, false, false, false,
        )
    }
}
