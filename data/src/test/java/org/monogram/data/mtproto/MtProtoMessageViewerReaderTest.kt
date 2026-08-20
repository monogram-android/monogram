package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.DialogPeerType
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.ReadParticipantDate_d00bb53fcf
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetMessageReadParticipants
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoMessageViewerReaderTest {
    @Test
    fun `loads projected message viewers through owned transport`() = runBlocking {
        val transport = RecordingTransport()
        val reader = MtProtoMessageViewerReaderImpl(
            configSource = TelegramMtProtoBootstrapConfigSource {
                TelegramMtProtoBootstrapConfig(
                    endpoint = TelegramMtProtoEndpoint(2, "dc", 443),
                    handshake = MtProtoHandshakeConfig(2, listOf("key")),
                    cloud = CloudLayer223ConnectionConfig(1, "test", "test", "test", "en"),
                )
            },
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = FakeUserStore,
            chats = NoOpMtProtoChatProjectionStore,
        )

        val viewers = reader.get(-7L, 8L)

        val request = transport.method as GetMessageReadParticipants
        assertEquals(org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat(7L), request.peer)
        assertEquals(8, request.msgId)
        assertEquals(9L, viewers.single().user.id)
        assertEquals(100, viewers.single().viewedDate)
        assertTrue(transport.closed)
    }

    private object FakeUserStore : MtProtoUserProjectionStore by NoOpMtProtoUserProjectionStore {
        override suspend fun get(scope: MtProtoAuthKeyScope, userId: Long): MtProtoUserReadModel? =
            MtProtoUserReadModel(
                userId = userId,
                accessHash = null,
                firstName = "Viewer",
                lastName = null,
                username = null,
                phone = null,
                isSelf = false,
                isContact = false,
                isMutualContact = false,
                isDeleted = false,
                isBot = false,
                isVerified = false,
                isRestricted = false,
                isScam = false,
                isFake = false,
                isPremium = false,
                isMin = false,
            )
    }

    private class RecordingTransport : MtProtoRpcTransport {
        lateinit var method: TlMethod<*>
        var closed = false

        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R {
            this.method = method
            return listOf(ReadParticipantDate_d00bb53fcf(9L, 100)) as R
        }

        override fun close() {
            closed = true
        }
    }
}
