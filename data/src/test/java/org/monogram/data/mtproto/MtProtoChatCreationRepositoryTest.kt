package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig

class MtProtoChatCreationRepositoryTest {
    @Test
    fun `rejects invalid group creation before opening transport`() = runBlocking {
        var opened = false
        val repository = MtProtoChatCreationRepositoryImpl(
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = MtProtoSessionTransportFactory { opened = true; error("transport must not open") },
            users = NoOpMtProtoUserProjectionStore,
            cloudObjectStager = NoOpMtProtoCloudObjectStager,
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.createGroup(" ", listOf(7), 0) }
        }
        assertEquals(false, opened)
    }

    @Test
    fun `rejects invalid channel auto delete time before opening transport`() = runBlocking {
        var opened = false
        val repository = MtProtoChatCreationRepositoryImpl(
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = MtProtoSessionTransportFactory { opened = true; error("transport must not open") },
            users = NoOpMtProtoUserProjectionStore,
            cloudObjectStager = NoOpMtProtoCloudObjectStager,
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.createChannel("News", "", false, -1) }
        }
        assertEquals(false, opened)
    }

    private fun config() = TelegramMtProtoBootstrapConfig(
        endpoint = TelegramMtProtoEndpoint(2, "dc", 443),
        handshake = MtProtoHandshakeConfig(2, listOf("key")),
        cloud = CloudLayer223ConnectionConfig(1, "device", "system", "app", "en"),
    )
}
