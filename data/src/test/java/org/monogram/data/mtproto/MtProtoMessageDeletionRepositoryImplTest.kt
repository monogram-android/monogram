package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.DialogPeerType
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.AffectedMessages_49c522afbd
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.DeleteMessages
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoMessageDeletionRepositoryImplTest {
    @Test
    fun `deletes basic-group messages through owned transport`() = runBlocking {
        val transport = RecordingTransport()
        val messages = RecordingMessages()
        val repository = MtProtoMessageDeletionRepositoryImpl(
            configSource = TelegramMtProtoBootstrapConfigSource {
                TelegramMtProtoBootstrapConfig(
                    TelegramMtProtoEndpoint(2, "dc", 443),
                    MtProtoHandshakeConfig(2, listOf("key")),
                    CloudLayer223ConnectionConfig(1, "test", "test", "test", "en"),
                )
            },
            transportFactory = MtProtoSessionTransportFactory { transport },
            chats = NoOpMtProtoChatProjectionStore,
            messages = messages,
        )

        repository.delete(-7L, DialogPeerType.BASIC_GROUP, listOf(3L, 4L), revoke = true)

        val request = transport.method as DeleteMessages
        assertEquals(listOf(3, 4), request.id)
        assertTrue(request.revoke)
        assertEquals(listOf(Triple(MtProtoMessagePeerType.GROUP, 7L, listOf(3, 4))), messages.deleted)
        assertTrue(transport.closed)
    }

    private class RecordingMessages : MtProtoMessageProjectionStore by NoOpMtProtoMessageProjectionStore {
        val deleted = mutableListOf<Triple<MtProtoMessagePeerType, Long, List<Int>>>()

        override suspend fun markDeleted(
            scope: MtProtoAuthKeyScope,
            peerType: MtProtoMessagePeerType,
            peerId: Long,
            messageIds: List<Int>,
        ) {
            deleted += Triple(peerType, peerId, messageIds)
        }
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
