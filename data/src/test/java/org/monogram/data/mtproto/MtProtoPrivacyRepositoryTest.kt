package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.AccountDaysTtl_f6ad918c54
import org.monogram.mtproto.tl.generated.cloud.layer223.PasswordKdfAlgoUnknown
import org.monogram.mtproto.tl.generated.cloud.layer223.SecurePasswordKdfAlgoUnknown
import org.monogram.mtproto.tl.generated.cloud.layer223.account.ContentSettings_33d483dc78
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerBlocked_161238e123
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.UserEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.account.GetAccountTtl
import org.monogram.mtproto.tl.generated.cloud.layer223.account.GetContentSettings
import org.monogram.mtproto.tl.generated.cloud.layer223.account.GetPassword
import org.monogram.mtproto.tl.generated.cloud.layer223.account.Password_ac67a26d5c
import org.monogram.mtproto.tl.generated.cloud.layer223.account.SetAccountTtl
import org.monogram.mtproto.tl.generated.cloud.layer223.account.SetContentSettings
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

    @Test
    fun `reads and updates account ttl through owned transport`() = runBlocking {
        val transport = Transport(listOf(AccountDaysTtl_f6ad918c54(180), true))
        val repository = MtProtoPrivacyRepository(Config { config() }, MtProtoSessionTransportFactory { transport }, Users())

        assertEquals(180, repository.getAccountTtl())
        repository.setAccountTtl(365)

        assertEquals(GetAccountTtl, transport.requests[0])
        assertEquals(SetAccountTtl(AccountDaysTtl_f6ad918c54(365)), transport.requests[1])
        assertTrue(transport.closed)
    }

    @Test
    fun `reads password state without exposing password material`() = runBlocking {
        val transport = Transport(listOf(password(hasPassword = true)))
        val repository = MtProtoPrivacyRepository(Config { config() }, MtProtoSessionTransportFactory { transport }, Users())

        assertEquals(true, repository.getPasswordState())
        assertEquals(GetPassword, transport.requests.single())
        assertTrue(transport.closed)
    }

    @Test
    fun `reads and updates sensitive content settings`() = runBlocking {
        val transport = Transport(
            listOf(
                ContentSettings_33d483dc78(sensitiveEnabled = true, sensitiveCanChange = false),
                ContentSettings_33d483dc78(sensitiveEnabled = false, sensitiveCanChange = true),
                true,
            ),
        )
        val repository = MtProtoPrivacyRepository(Config { config() }, MtProtoSessionTransportFactory { transport }, Users())

        assertEquals(false, repository.canShowSensitiveContent())
        assertEquals(false, repository.isShowSensitiveContentEnabled())
        repository.setShowSensitiveContent(true)

        assertEquals(GetContentSettings, transport.requests[0])
        assertEquals(GetContentSettings, transport.requests[1])
        assertEquals(SetContentSettings(true), transport.requests[2])
        assertTrue(transport.closed)
    }

    @Test
    fun `rejects invalid account ttl before opening transport`() = runBlocking {
        var opened = false
        val repository = MtProtoPrivacyRepository(
            Config { config() },
            MtProtoSessionTransportFactory { opened = true; Transport(emptyList()) },
            Users(),
        )

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.setAccountTtl(0) }
        }
        assertEquals(false, opened)
    }

    private fun password(hasPassword: Boolean) = Password_ac67a26d5c(
        hasRecovery = false,
        hasSecureValues = false,
        hasPassword = hasPassword,
        currentAlgo = null,
        srpB = null,
        srpId = null,
        hint = null,
        emailUnconfirmedPattern = null,
        newAlgo = PasswordKdfAlgoUnknown,
        newSecureAlgo = SecurePasswordKdfAlgoUnknown,
        secureRandom = org.monogram.mtproto.tl.runtime.TlBytes.copyOf(byteArrayOf()),
        pendingResetDate = null,
        loginEmailPattern = null,
    )

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
