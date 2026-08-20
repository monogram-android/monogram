package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.monogram.data.mtproto.MtProtoChatInfoRepository
import org.monogram.data.mtproto.TelegramMtProtoBootstrapConfigSource
import org.monogram.data.mtproto.TelegramMtProtoBootstrapConfig
import org.monogram.data.mtproto.TelegramMtProtoEndpoint
import org.monogram.data.mtproto.NoOpMtProtoChatProjectionStore
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.repository.ChatInfoRepository

class TelegramBackendChatInfoRouterTest {
    @Test
    fun `selected MTProto private chat info does not construct legacy repository`() = runBlocking {
        val mtProto = MtProtoChatInfoRepository(
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = null,
            chats = NoOpMtProtoChatProjectionStore,
        )
        val router = TelegramBackendChatInfoRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy chat info must not be created") },
            mtProto = mtProto,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertNull(router.getChatFullInfo(TelegramPeerChatId.encode(DialogPeerType.PRIVATE, 42)))
    }

    private fun config() = TelegramMtProtoBootstrapConfig(
        endpoint = TelegramMtProtoEndpoint(4, "dc", 443),
        handshake = MtProtoHandshakeConfig(4, listOf("test-key")),
        cloud = CloudLayer223ConnectionConfig(12345, "device", "system", "app", "en"),
    )

    private class FakeSelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        private val state = MutableStateFlow(initial)
        override suspend fun get(accountId: String) = state.value
        override fun observe(accountId: String): Flow<TelegramBackendKind> = state
        override suspend fun select(accountId: String, backend: TelegramBackendKind) { state.value = backend }
        override suspend fun reset(accountId: String) { state.value = TelegramBackendKind.LEGACY }
    }
}
