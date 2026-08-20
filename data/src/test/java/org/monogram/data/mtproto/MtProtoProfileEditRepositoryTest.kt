package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.UserEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.account.UpdateProfile
import org.monogram.mtproto.tl.generated.cloud.layer223.account.UpdateUsername
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoProfileEditRepositoryTest {
    @Test
    fun `updates name and stages acknowledged user`() = runBlocking {
        val transport = Transport(UserEmpty(7))
        val users = Users()
        val repository = MtProtoProfileEditRepository(Config { config() }, MtProtoSessionTransportFactory { transport }, users)

        repository.setName("Ada", "Lovelace")

        assertEquals(UpdateProfile("Ada", "Lovelace", null), transport.request)
        assertEquals(listOf(7L), users.userIds)
        assertTrue(transport.closed)
    }

    @Test
    fun `updates username and stages acknowledged user`() = runBlocking {
        val transport = Transport(UserEmpty(7))
        val users = Users()
        val repository = MtProtoProfileEditRepository(Config { config() }, MtProtoSessionTransportFactory { transport }, users)

        repository.setUsername("ada")

        assertEquals(UpdateUsername("ada"), transport.request)
        assertEquals(listOf(7L), users.userIds)
        assertTrue(transport.closed)
    }

    @Test
    fun `updates bio without replacing name`() = runBlocking {
        val transport = Transport(UserEmpty(7))
        val repository = MtProtoProfileEditRepository(Config { config() }, MtProtoSessionTransportFactory { transport }, Users())

        repository.setBio("Mathematician")

        assertEquals(UpdateProfile(null, null, "Mathematician"), transport.request)
    }

    private fun config() = TelegramMtProtoBootstrapConfig(
        TelegramMtProtoEndpoint(2, "dc", 443),
        MtProtoHandshakeConfig(2, listOf("key")),
        CloudLayer223ConnectionConfig(1, "d", "s", "a", "en"),
    )

    private class Config(
        private val value: suspend () -> TelegramMtProtoBootstrapConfig,
    ) : TelegramMtProtoBootstrapConfigSource {
        override suspend fun create() = value()
    }

    private class Transport(private val response: Any) : MtProtoRpcTransport {
        lateinit var request: TlMethod<*>
        var closed = false

        override suspend fun <R> execute(method: TlMethod<R>): R {
            request = method
            @Suppress("UNCHECKED_CAST")
            return response as R
        }

        override fun close() {
            closed = true
        }
    }

    private class Users : MtProtoUserProjectionStore by NoOpMtProtoUserProjectionStore {
        val userIds = mutableListOf<Long>()

        override suspend fun upsert(
            scope: MtProtoAuthKeyScope,
            users: List<org.monogram.mtproto.tl.generated.cloud.layer223.User_655b5dfc57>,
        ) {
            userIds += users.map { (it as UserEmpty).id }
        }
    }
}
