package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.BirthdateModel
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.Birthday_aa6c995ca2
import org.monogram.mtproto.tl.generated.cloud.layer223.InputBusinessIntro_7df76090c9
import org.monogram.mtproto.tl.generated.cloud.layer223.InputGeoPoint_ca056caf04
import org.monogram.mtproto.tl.generated.cloud.layer223.EmojiStatusEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.EmojiStatus_c46bf14186
import org.monogram.mtproto.tl.generated.cloud.layer223.UserEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.account.ReorderUsernames
import org.monogram.mtproto.tl.generated.cloud.layer223.account.ToggleUsername
import org.monogram.mtproto.tl.generated.cloud.layer223.account.UpdateBusinessIntro
import org.monogram.mtproto.tl.generated.cloud.layer223.account.UpdateBusinessLocation
import org.monogram.mtproto.tl.generated.cloud.layer223.account.UpdateBirthday
import org.monogram.mtproto.tl.generated.cloud.layer223.account.UpdateEmojiStatus
import org.monogram.mtproto.tl.generated.cloud.layer223.account.UpdatePersonalChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputChannel_d22292516d
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

    @Test
    fun `updates and clears emoji status through authoritative bool`() = runBlocking {
        val transport = Transport(true)
        val repository = MtProtoProfileEditRepository(Config { config() }, MtProtoSessionTransportFactory { transport }, Users())

        repository.setEmojiStatus(42)
        assertEquals(UpdateEmojiStatus(EmojiStatus_c46bf14186(42, null)), transport.request)
        assertTrue(transport.closed)

        repository.setEmojiStatus(null)
        assertEquals(UpdateEmojiStatus(EmojiStatusEmpty), transport.request)
    }

    @Test
    fun `updates and clears birthday through authoritative bool`() = runBlocking {
        val transport = Transport(true)
        val repository = MtProtoProfileEditRepository(Config { config() }, MtProtoSessionTransportFactory { transport }, Users())

        repository.setBirthdate(BirthdateModel(day = 2, month = 3, year = 2000))
        assertEquals(UpdateBirthday(Birthday_aa6c995ca2(2, 3, 2000)), transport.request)
        assertTrue(transport.closed)

        repository.setBirthdate(null)
        assertEquals(UpdateBirthday(null), transport.request)
    }

    @Test
    fun `rejects invalid birthday before opening transport`() = runBlocking {
        var opened = false
        val repository = MtProtoProfileEditRepository(
            Config { config() },
            MtProtoSessionTransportFactory { opened = true; Transport(true) },
            Users(),
        )

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.setBirthdate(BirthdateModel(day = 0, month = 3)) }
        }
        assertEquals(false, opened)
    }

    @Test
    fun `updates business location through authoritative bool`() = runBlocking {
        val transport = Transport(true)
        val repository = MtProtoProfileEditRepository(Config { config() }, MtProtoSessionTransportFactory { transport }, Users())

        repository.setBusinessLocation("HQ", 12.5, -45.25)

        assertEquals(UpdateBusinessLocation(InputGeoPoint_ca056caf04(12.5, -45.25, null), "HQ"), transport.request)
        assertTrue(transport.closed)
    }

    @Test
    fun `updates business bio through authoritative bool`() = runBlocking {
        val transport = Transport(true)
        val repository = MtProtoProfileEditRepository(Config { config() }, MtProtoSessionTransportFactory { transport }, Users())

        repository.setBusinessBio("Welcome")

        assertEquals(UpdateBusinessIntro(InputBusinessIntro_7df76090c9("", "Welcome", null)), transport.request)
        assertTrue(transport.closed)
    }

    @Test
    fun `toggles and reorders usernames through authoritative bool`() = runBlocking {
        val transport = Transport(true)
        val repository = MtProtoProfileEditRepository(Config { config() }, MtProtoSessionTransportFactory { transport }, Users())

        repository.toggleUsernameIsActive("alice", true)
        assertEquals(ToggleUsername("alice", true), transport.request)
        assertTrue(transport.closed)

        repository.reorderActiveUsernames(listOf("alice", "ada"))
        assertEquals(ReorderUsernames(listOf("alice", "ada")), transport.request)
    }

    @Test
    fun `rejects invalid username lists before opening transport`() = runBlocking {
        var opened = false
        val repository = MtProtoProfileEditRepository(
            Config { config() },
            MtProtoSessionTransportFactory { opened = true; Transport(true) },
            Users(),
        )

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.reorderActiveUsernames(listOf("alice", "alice")) }
        }
        assertEquals(false, opened)
    }

    @Test
    fun `sets personal channel from projected access hash`() = runBlocking {
        val transport = Transport(true)
        val channelId = 42L
        val repository = MtProtoProfileEditRepository(
            Config { config() },
            MtProtoSessionTransportFactory { transport },
            Users(),
            chats = object : MtProtoChatProjectionStore by NoOpMtProtoChatProjectionStore {
                override suspend fun get(scope: MtProtoAuthKeyScope, chatId: Long) = MtProtoChatReadModel(
                    chatId, MtProtoChatType.CHANNEL, 9, null, null, null,
                    false, false, false, false, false, false, false, false, false, false, false, false, false,
                )
            },
        )

        repository.setPersonalChat(-1_000_000_000_042L)

        assertEquals(UpdatePersonalChannel(InputChannel_d22292516d(channelId, 9)), transport.request)
        assertTrue(transport.closed)
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
