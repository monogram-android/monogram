package org.monogram.data.mtproto

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.repository.DialogSnapshotRepository
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.InputNotifyPeer_c75b710401
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerNotifySettings_6185e07dc9
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.account.UpdateNotifySettings
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoMuteRepositoryTest {
    @Test
    fun `mutes resolved user peer and refreshes dialogs`() = runTest {
        val transport = RecordingTransport()
        val dialogs = RecordingDialogRepository()
        val repository = MtProtoMuteRepositoryImpl(
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = FakeUserStore(),
            chats = NoOpMtProtoChatProjectionStore,
            dialogs = dialogs,
        )

        repository.setMuted(setOf(42L), muted = true)

        val request = transport.requests.single() as UpdateNotifySettings
        assertEquals(InputPeerUser(42L, 99L), (request.peer as InputNotifyPeer_c75b710401).peer)
        assertEquals(Int.MAX_VALUE, (request.settings as InputPeerNotifySettings_6185e07dc9).muteUntil)
        assertEquals(1, dialogs.calls)
        assertTrue(transport.closed)
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

    private class RecordingDialogRepository : DialogSnapshotRepository {
        var calls = 0
        override suspend fun getDialogs(accountId: String) =
            emptyList<org.monogram.domain.models.DialogSnapshotModel>().also { calls++ }
    }
}
