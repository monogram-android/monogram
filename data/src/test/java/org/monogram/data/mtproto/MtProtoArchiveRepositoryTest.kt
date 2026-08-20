package org.monogram.data.mtproto

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.repository.DialogSnapshotRepository
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.InputFolderPeer_752d9a4fbc
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesTooLong
import org.monogram.mtproto.tl.generated.cloud.layer223.folders.EditPeerFolders
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoArchiveRepositoryTest {
    @Test
    fun `archives resolved user peer and refreshes persisted dialogs`() = runTest {
        val transport = RecordingTransport()
        val dialogs = RecordingDialogRepository()
        val repository = MtProtoArchiveRepositoryImpl(
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = FakeUserStore(),
            chats = NoOpMtProtoChatProjectionStore,
            dialogs = dialogs,
        )

        repository.setArchived(setOf(42L), archived = true)

        val request = transport.requests.single() as EditPeerFolders
        val peer = request.folderPeers.single() as InputFolderPeer_752d9a4fbc
        assertEquals(1, request.folderPeers.size)
        assertEquals(1, peer.folderId)
        assertEquals(InputPeerUser(42L, 99L), peer.peer)
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
            return UpdatesTooLong as R
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeUserStore : MtProtoUserProjectionStore by NoOpMtProtoUserProjectionStore {
        override suspend fun get(scope: MtProtoAuthKeyScope, userId: Long) = MtProtoUserReadModel(
            userId = userId,
            accessHash = 99L,
            firstName = "User",
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

    private class RecordingDialogRepository : DialogSnapshotRepository {
        var calls = 0
        override suspend fun getDialogs(accountId: String) = emptyList<org.monogram.domain.models.DialogSnapshotModel>().also { calls++ }
    }
}
