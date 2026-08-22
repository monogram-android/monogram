package org.monogram.data.mtproto

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.repository.DialogSnapshotRepository
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesTooLong
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.DeleteChatUser
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoLeaveChatRepositoryTest {
    @Test
    fun `leaves a basic group with input user self and refreshes dialogs`() = runTest {
        val transport = RecordingTransport()
        val dialogs = RecordingDialogRepository()
        val repository = MtProtoLeaveChatRepositoryImpl(
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = MtProtoSessionTransportFactory { transport },
            chats = NoOpMtProtoChatProjectionStore,
            cloudObjectStager = NoOpMtProtoCloudObjectStager,
            dialogs = dialogs,
        )

        repository.leave(setOf(-42L))

        val request = transport.requests.single() as DeleteChatUser
        assertEquals(42L, request.chatId)
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
        override fun close() { closed = true }
    }

    private class RecordingDialogRepository : DialogSnapshotRepository {
        var calls = 0
        override suspend fun getDialogs(accountId: String) =
            emptyList<org.monogram.domain.models.DialogSnapshotModel>().also { calls++ }
    }
}
