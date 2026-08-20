package org.monogram.data.mtproto

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesTooLong
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.EditChatAbout
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.EditChatTitle
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoChatSettingsRepositoryTest {
    @Test
    fun `edits basic group title and stages authoritative updates`() = runTest {
        val transport = RecordingTransport()
        val stager = RecordingStager()
        val repository = repository(transport, stager)

        repository.setTitle(-42, "Team")

        val request = transport.requests.single() as EditChatTitle
        assertEquals(42L, request.chatId)
        assertEquals("Team", request.title)
        assertEquals(1, stager.calls)
        assertTrue(transport.closed)
    }

    @Test
    fun `edits basic group description without fabricating projection`() = runTest {
        val transport = RecordingTransport()
        val stager = RecordingStager()
        val repository = repository(transport, stager)

        repository.setDescription(-42, "Owned MTProto")

        val request = transport.requests.single() as EditChatAbout
        assertEquals("Owned MTProto", request.about)
        assertEquals(0, stager.calls)
        assertTrue(transport.closed)
    }

    private fun repository(transport: RecordingTransport, stager: RecordingStager) = MtProtoChatSettingsRepositoryImpl(
        configSource = TelegramMtProtoBootstrapConfigSource { config() },
        transportFactory = MtProtoSessionTransportFactory { transport },
        chats = NoOpMtProtoChatProjectionStore,
        cloudObjectStager = stager,
    )

    private fun config() = TelegramMtProtoBootstrapConfig(
        endpoint = TelegramMtProtoEndpoint(2, "dc", 443),
        handshake = MtProtoHandshakeConfig(2, listOf("key")),
        cloud = CloudLayer223ConnectionConfig(1, "device", "system", "app", "en"),
    )

    private class RecordingTransport : MtProtoRpcTransport {
        val requests = mutableListOf<TlMethod<*>>()
        var closed = false
        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R {
            requests += method
            return when (method) {
                is EditChatAbout -> true as R
                else -> UpdatesTooLong as R
            }
        }
        override fun close() { closed = true }
    }

    private class RecordingStager : MtProtoCloudObjectStager by NoOpMtProtoCloudObjectStager {
        var calls = 0
        override suspend fun stageLive(scope: MtProtoAuthKeyScope, envelope: org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5) {
            calls++
        }
    }
}
