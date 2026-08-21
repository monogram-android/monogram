package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.monogram.domain.models.UserProfileSnapshotModel
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.ChatEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.UserEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.Contact_fd1b8c949c
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.Contacts_9469c223cd
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.EditCloseFriends
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.Found_bc39b7fc74
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.GetContacts
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.Search
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoUserProfileSnapshotRepositoryTest {
    @Test
    fun `maps current user projection without exposing backend fields`() = runBlocking {
        val store = RecordingUserStore(profile())
        val repository = MtProtoUserProfileSnapshotRepository(
            configSource = TelegramMtProtoBootstrapConfigSource { config(dcId = 4) },
            userStore = store,
        )

        val profile = repository.getCurrentUser("account-1")

        assertEquals(MtProtoAuthKeyScope("account-1", MtProtoEnvironment.PRODUCTION, 4), store.selfScope)
        assertEquals(
            UserProfileSnapshotModel(
                userId = 10,
                firstName = "Alice",
                lastName = "Smith",
                username = "alice",
                phoneNumber = "123",
                isCurrentUser = true,
                isContact = true,
                isMutualContact = true,
                isDeleted = false,
                isBot = false,
                isVerified = true,
                isRestricted = false,
                isScam = false,
                isFake = false,
                isPremium = true,
                isPartial = false,
            ),
            profile,
        )
    }

    @Test
    fun `searches contacts and stages returned peer projections`() = runBlocking {
        val users = SearchingUserStore()
        val chats = RecordingChatStore()
        val transport = Transport(
            Found_bc39b7fc74(
                myResults = listOf(PeerUser(8)),
                results = listOf(PeerChannel(9), PeerUser(7), PeerUser(8)),
                chats = listOf(ChatEmpty(9)),
                users = listOf(UserEmpty(7), UserEmpty(8)),
            ),
        )
        val repository = MtProtoUserProfileSnapshotRepository(
            configSource = TelegramMtProtoBootstrapConfigSource { config(dcId = 2) },
            userStore = users,
            chatStore = chats,
            sessionFactory = MtProtoSessionTransportFactory { transport },
        )

        assertEquals(listOf(8L, 7L), repository.searchContacts("account-2", "ada").map { it.userId })
        assertEquals(Search("ada", 100), transport.request)
        assertEquals(listOf(7L, 8L), users.upsertedIds)
        assertEquals(listOf(9L), chats.upsertedIds)
        assertEquals(true, transport.closed)
    }

    @Test
    fun `updates close friends from an authoritative contact list`() = runBlocking {
        val users = SearchingUserStore()
        val transport = Transport(
            Contacts_9469c223cd(
                contacts = listOf(Contact_fd1b8c949c(userId = 7, mutual = false)),
                savedCount = 0,
                users = listOf(UserEmpty(7)),
            ),
            true,
        )
        val repository = MtProtoUserProfileSnapshotRepository(
            configSource = TelegramMtProtoBootstrapConfigSource { config(dcId = 2) },
            userStore = users,
            sessionFactory = MtProtoSessionTransportFactory { transport },
        )

        repository.setCloseFriend("account-2", userId = 7, isCloseFriend = true)

        assertEquals(listOf(GetContacts(0L), EditCloseFriends(listOf(7L))), transport.requests)
        assertEquals(listOf(7L), users.upsertedIds)
        assertEquals(true, transport.closed)
    }

    @Test
    fun `loads requested user in production scope and preserves absence`() = runBlocking {
        val store = RecordingUserStore(profile = null)
        val repository = MtProtoUserProfileSnapshotRepository(
            configSource = TelegramMtProtoBootstrapConfigSource { config(dcId = 2) },
            userStore = store,
        )

        val profile = repository.getUser("account-2", 30)

        assertNull(profile)
        assertEquals(MtProtoAuthKeyScope("account-2", MtProtoEnvironment.PRODUCTION, 2), store.userScope)
        assertEquals(30L, store.userId)
    }

    private fun profile() = MtProtoUserReadModel(
        userId = 10,
        accessHash = 99,
        firstName = "Alice",
        lastName = "Smith",
        username = "alice",
        phone = "123",
        isSelf = true,
        isContact = true,
        isMutualContact = true,
        isDeleted = false,
        isBot = false,
        isVerified = true,
        isRestricted = false,
        isScam = false,
        isFake = false,
        isPremium = true,
        isMin = false,
    )

    private fun config(dcId: Int) = TelegramMtProtoBootstrapConfig(
        endpoint = TelegramMtProtoEndpoint(dcId, "dc", 443),
        handshake = MtProtoHandshakeConfig(dcId, listOf("test-key")),
        cloud = CloudLayer223ConnectionConfig(
            apiId = 12345,
            deviceModel = "device",
            systemVersion = "system",
            applicationVersion = "app",
            systemLanguageCode = "en",
        ),
    )

    private class SearchingUserStore : MtProtoUserProjectionStore by NoOpMtProtoUserProjectionStore {
        val upsertedIds = mutableListOf<Long>()

        override suspend fun upsert(scope: MtProtoAuthKeyScope, users: List<org.monogram.mtproto.tl.generated.cloud.layer223.User_655b5dfc57>) {
            upsertedIds += users.map { (it as UserEmpty).id }
        }

        override suspend fun get(scope: MtProtoAuthKeyScope, userId: Long) = MtProtoUserReadModel(
            userId = userId,
            accessHash = null,
            firstName = "User$userId",
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

    private class RecordingChatStore : MtProtoChatProjectionStore by NoOpMtProtoChatProjectionStore {
        val upsertedIds = mutableListOf<Long>()

        override suspend fun upsert(scope: MtProtoAuthKeyScope, chats: List<org.monogram.mtproto.tl.generated.cloud.layer223.Chat_7fdd7beb6e>) {
            upsertedIds += chats.map { (it as ChatEmpty).id }
        }
    }

    private class Transport(private vararg val responses: Any) : MtProtoRpcTransport {
        val requests = mutableListOf<TlMethod<*>>()
        val request: TlMethod<*> get() = requests.last()
        var closed = false
        private var responseIndex = 0

        override suspend fun <R> execute(method: TlMethod<R>): R {
            requests += method
            @Suppress("UNCHECKED_CAST")
            return responses[responseIndex++] as R
        }

        override fun close() {
            closed = true
        }
    }

    private class RecordingUserStore(
        private val profile: MtProtoUserReadModel?,
    ) : MtProtoUserProjectionStore by NoOpMtProtoUserProjectionStore {
        var selfScope: MtProtoAuthKeyScope? = null
        var userScope: MtProtoAuthKeyScope? = null
        var userId: Long? = null

        override suspend fun getSelf(scope: MtProtoAuthKeyScope): MtProtoUserReadModel? {
            selfScope = scope
            return profile
        }

        override suspend fun get(scope: MtProtoAuthKeyScope, userId: Long): MtProtoUserReadModel? {
            userScope = scope
            this.userId = userId
            return profile
        }
    }
}
