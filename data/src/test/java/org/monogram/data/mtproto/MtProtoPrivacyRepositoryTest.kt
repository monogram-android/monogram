package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerBlocked_161238e123
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.UserEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.Block
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.BlockedSlice
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.GetBlocked
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.Unblock
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoPrivacyRepositoryTest {
    @Test
    fun `blocks and unblocks projected user`() = runBlocking {
        val transport = Transport(listOf(true, true))
        val repository = MtProtoPrivacyRepository(Config { config() }, MtProtoSessionTransportFactory { transport }, Users())

        repository.blockUser(42L)
        repository.unblockUser(42L)

        assertEquals(InputPeerUser(42L, 99L), (transport.requests[0] as Block).id)
        assertEquals(InputPeerUser(42L, 99L), (transport.requests[1] as Unblock).id)
        assertTrue(transport.closed)
    }

    @Test
    fun `pages blocked users and stages projections after all pages`() = runBlocking {
        val transport = Transport(
            listOf(
                BlockedSlice(2, listOf(blocked(1)), emptyList(), listOf(UserEmpty(1))),
                BlockedSlice(2, listOf(blocked(2)), emptyList(), listOf(UserEmpty(2))),
            ),
        )
        val users = Users()
        val repository = MtProtoPrivacyRepository(Config { config() }, MtProtoSessionTransportFactory { transport }, users)

        assertEquals(listOf(1L, 2L), repository.getBlockedUsers())
        assertEquals(listOf(0, 1), transport.requests.filterIsInstance<GetBlocked>().map { it.offset })
        assertEquals(listOf(listOf(1L, 2L)), users.upserts)
        assertTrue(transport.closed)
    }

    @Test
    fun `does not stage blocked users when a later page fails`() = runBlocking {
        val transport = Transport(
            listOf(
                BlockedSlice(2, listOf(blocked(1)), emptyList(), listOf(UserEmpty(1))),
                IllegalStateException("network failure"),
            ),
        )
        val users = Users()
        val repository = MtProtoPrivacyRepository(Config { config() }, MtProtoSessionTransportFactory { transport }, users)

        try {
            repository.getBlockedUsers()
            error("expected paging failure")
        } catch (expected: IllegalStateException) {
            assertEquals("network failure", expected.message)
        }

        assertEquals(emptyList<List<Long>>(), users.upserts)
        assertTrue(transport.closed)
    }

    private fun blocked(userId: Long) = PeerBlocked_161238e123(PeerUser(userId), 0)

    private fun config() = TelegramMtProtoBootstrapConfig(
        TelegramMtProtoEndpoint(2, "dc", 443),
        MtProtoHandshakeConfig(2, listOf("key")),
        CloudLayer223ConnectionConfig(1, "d", "s", "a", "en"),
    )

    private class Config(
        val value: suspend () -> TelegramMtProtoBootstrapConfig,
    ) : TelegramMtProtoBootstrapConfigSource {
        override suspend fun create() = value()
    }

    private class Transport(responses: List<Any>) : MtProtoRpcTransport {
        private val responses = ArrayDeque(responses)
        val requests = mutableListOf<TlMethod<*>>()
        var closed = false

        override suspend fun <R> execute(method: TlMethod<R>): R {
            requests += method
            val response = responses.removeFirst()
            if (response is Throwable) throw response
            @Suppress("UNCHECKED_CAST")
            return response as R
        }

        override fun close() {
            closed = true
        }
    }

    private class Users : MtProtoUserProjectionStore by NoOpMtProtoUserProjectionStore {
        val upserts = mutableListOf<List<Long>>()

        override suspend fun get(scope: MtProtoAuthKeyScope, userId: Long) = MtProtoUserReadModel(
            userId, 99L, null, null, null, null, false, false, false, false, false, false,
            false, false, false, false, false,
        )

        override suspend fun upsert(
            scope: MtProtoAuthKeyScope,
            users: List<org.monogram.mtproto.tl.generated.cloud.layer223.User_655b5dfc57>,
        ) {
            upserts += users.map {
                when (it) {
                    is UserEmpty -> it.id
                    else -> error("unexpected user")
                }
            }
        }
    }
}
