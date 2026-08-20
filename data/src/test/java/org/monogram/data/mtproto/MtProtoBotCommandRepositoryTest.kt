package org.monogram.data.mtproto

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.BotCommandScopePeer
import org.monogram.mtproto.tl.generated.cloud.layer223.BotCommand_0a423bcf36
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.bots.GetBotCommands
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoBotCommandRepositoryTest {
    @Test
    fun `uses persisted bot identity for direct chat command scope`() = runTest {
        val transport = RecordingTransport()
        val repository = MtProtoBotCommandRepositoryImpl(
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = BotStore,
        )

        assertEquals(listOf("start"), repository.getCommands(7).map { it.command })
        val request = transport.requests.single() as GetBotCommands
        assertEquals("", request.langCode)
        assertEquals(InputPeerUser(7, 9), (request.scope as BotCommandScopePeer).peer)
        assertTrue(transport.closed)
    }

    private object BotStore : MtProtoUserProjectionStore by NoOpMtProtoUserProjectionStore {
        override suspend fun get(scope: MtProtoAuthKeyScope, userId: Long) = MtProtoUserReadModel(
            userId = userId, accessHash = 9, firstName = "Bot", lastName = null, username = null, phone = null,
            isSelf = false, isContact = false, isMutualContact = false, isDeleted = false, isBot = true,
            isVerified = false, isRestricted = false, isScam = false, isFake = false, isPremium = false, isMin = false,
        )
    }

    private class RecordingTransport : MtProtoRpcTransport {
        val requests = mutableListOf<TlMethod<*>>()
        var closed = false
        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R {
            requests += method
            return listOf(BotCommand_0a423bcf36("start", "Begin")) as R
        }
        override fun close() { closed = true }
    }

    private fun config() = TelegramMtProtoBootstrapConfig(
        endpoint = TelegramMtProtoEndpoint(2, "dc", 443),
        handshake = MtProtoHandshakeConfig(2, listOf("key")),
        cloud = CloudLayer223ConnectionConfig(1, "device", "system", "app", "en"),
    )
}
