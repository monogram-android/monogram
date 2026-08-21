package org.monogram.data.mtproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.db.dao.SearchHistoryDao
import org.monogram.data.db.model.SearchHistoryEntity
import org.monogram.domain.models.MessageContent
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.domain.models.DialogMessagePreviewModel
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.DialogSnapshotModel
import org.monogram.domain.repository.DialogSnapshotRepository
import org.monogram.mtproto.tl.generated.cloud.layer223.InputMessagesFilterEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.User_655b5dfc57
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.Found_bc39b7fc74
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.MessagesSlice
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.SearchGlobal
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoChatSearchRepositoryTest {
    @Test
    fun `searches projected dialogs by title and username case insensitively`() = runBlocking {
        val repository = MtProtoChatSearchRepository(FakeDialogRepository())

        val results = repository.searchChats("ALICE")

        assertEquals(1, results.size)
        assertEquals("Alice", results.single().title)
        assertEquals(4, results.single().unreadCount)
    }

    @Test
    fun `search history fails closed without persistence`() = runBlocking {
        val repository = MtProtoChatSearchRepository(FakeDialogRepository())

        val failure = runCatching { repository.searchHistory.first() }.exceptionOrNull()
        assertTrue(failure is UnsupportedOperationException)
    }

    @Test
    fun `searches projected messages with encoded peer ids and pagination`() = runBlocking {
        val repository = MtProtoChatSearchRepository(
            dialogRepository = FakeDialogRepository(),
            messageStore = FakeMessageStore(
                listOf(
                    message(MtProtoMessagePeerType.USER, 42, 10, "first match"),
                    message(MtProtoMessagePeerType.CHANNEL, 99, 11, "second match"),
                )
            ),
            configSource = TelegramMtProtoBootstrapConfigSource { testConfig() },
        )

        val first = repository.searchMessages("match", limit = 1)
        val second = repository.searchMessages("match", offset = first.nextOffset, limit = 1)

        assertEquals(1, first.messages.size)
        assertEquals(10L, first.messages.single().id)
        assertEquals(42L, first.messages.single().chatId)
        assertEquals(MessageContent.Text("first match"), first.messages.single().content)
        assertEquals(1, second.messages.size)
        assertEquals(11L, second.messages.single().id)
    }

    @Test
    fun `searches messages through MTProto and returns a server cursor`() = runBlocking {
        val messages = RecordingSearchMessageStore()
        val transport = RecordingSearchTransport(
            MessagesSlice(
                inexact = false,
                count = 2,
                nextRate = 9,
                offsetIdOffset = null,
                searchFlood = null,
                messages = listOf(MessageEmpty(8, PeerUser(42L))),
                topics = emptyList(),
                chats = emptyList(),
                users = emptyList(),
            ),
        )
        val users = RecordingUserStore()
        val repository = MtProtoChatSearchRepository(
            dialogRepository = FakeDialogRepository(),
            messageStore = messages,
            configSource = TelegramMtProtoBootstrapConfigSource { testConfig() },
            transportFactory = MtProtoSessionTransportFactory { transport },
            userStore = users,
            chatStore = NoOpMtProtoChatProjectionStore,
            resultStager = MtProtoHistoryResultStager(users, NoOpMtProtoChatProjectionStore, messages),
        )

        val result = repository.searchMessages("match", limit = 10)

        val request = transport.method as SearchGlobal
        assertEquals("match", request.q)
        assertEquals(InputMessagesFilterEmpty, request.filter)
        assertEquals(InputPeerEmpty, request.offsetPeer)
        assertEquals("9:42:8", result.nextOffset)
        assertEquals(listOf(8L), result.messages.map { it.id })
    }

    @Test
    fun `searches public peers through MTProto and stages returned users`() = runBlocking {
        val users = RecordingUserStore()
        val repository = MtProtoChatSearchRepository(
            dialogRepository = FakeDialogRepository(),
            configSource = TelegramMtProtoBootstrapConfigSource { testConfig() },
            transportFactory = MtProtoSessionTransportFactory { FakeTransport(
                Found_bc39b7fc74(
                    myResults = listOf(PeerUser(43L)),
                    results = listOf(PeerUser(42L), PeerUser(43L)),
                    chats = emptyList(),
                    users = emptyList(),
                ),
            ) },
            userStore = users,
            chatStore = NoOpMtProtoChatProjectionStore,
        )

        val result = repository.searchPublicChats("alice")

        assertEquals(listOf(43L, 42L), result.map { it.id })
        assertEquals(1, users.upsertCalls)
    }

    @Test
    fun `persists selected MTProto search history and resolves projected dialogs`() = runBlocking {
        val dao = RecordingSearchHistoryDao(listOf(SearchHistoryEntity(42)))
        val repository = MtProtoChatSearchRepository(
            dialogRepository = FakeDialogRepository(),
            searchHistoryDao = dao,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(listOf(42L), repository.searchHistory.first().map { it.id })
        repository.addSearchChatId(43)
        repository.removeSearchChatId(42)
        repository.clearSearchHistory()

        assertEquals(listOf(43L), dao.inserted)
        assertEquals(listOf(42L), dao.deleted)
        assertEquals(1, dao.clearCalls)
    }

    @Test
    fun `unsupported search operations fail closed`(): Unit = runBlocking {
        val repository = MtProtoChatSearchRepository(FakeDialogRepository())

        assertThrows(UnsupportedOperationException::class.java) {
            runBlocking { repository.searchPublicChats("alice") }
        }
        assertThrows(UnsupportedOperationException::class.java) {
            runBlocking { repository.searchMessages("hello") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.searchMessages("hello", offset = "not-a-number") }
        }
    }

    private fun message(peerType: MtProtoMessagePeerType, peerId: Long, id: Int, text: String) = MtProtoMessageReadModel(
        peerType = peerType,
        peerId = peerId,
        messageId = id,
        senderType = null,
        senderId = 7,
        date = id,
        text = text,
        isService = false,
        isDeleted = false,
        isOutgoing = false,
        isMentioned = false,
        isMediaUnread = false,
        isSilent = false,
        isPinned = false,
        editDate = null,
        groupedId = null,
        hasMedia = false,
    )

    private fun testConfig() = TelegramMtProtoBootstrapConfig(
        endpoint = TelegramMtProtoEndpoint(4, "dc", 443),
        handshake = MtProtoHandshakeConfig(4, listOf("test-key")),
        cloud = CloudLayer223ConnectionConfig(12345, "device", "system", "app", "en"),
    )

    private class RecordingSearchHistoryDao(
        private val history: List<SearchHistoryEntity>,
    ) : SearchHistoryDao {
        val inserted = mutableListOf<Long>()
        val deleted = mutableListOf<Long>()
        var clearCalls = 0

        override fun getSearchHistory(): Flow<List<SearchHistoryEntity>> = flowOf(history)
        override suspend fun insertSearchChatId(entity: SearchHistoryEntity) { inserted += entity.chatId }
        override suspend fun deleteSearchChatId(chatId: Long) { deleted += chatId }
        override suspend fun clearAll() { clearCalls++ }
    }

    private class FakeTransport(private val result: Any) : MtProtoRpcTransport {
        override suspend fun <R> execute(method: TlMethod<R>): R {
            @Suppress("UNCHECKED_CAST")
            return result as R
        }

        override fun close() = Unit
    }

    private class RecordingUserStore : MtProtoUserProjectionStore by NoOpMtProtoUserProjectionStore {
        var upsertCalls = 0

        override suspend fun upsert(scope: MtProtoAuthKeyScope, users: List<User_655b5dfc57>) {
            upsertCalls++
        }

        override suspend fun get(scope: MtProtoAuthKeyScope, userId: Long) = MtProtoUserReadModel(
            userId = userId,
            accessHash = 1L,
            firstName = "Alice",
            lastName = null,
            username = "alice",
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

    private class RecordingSearchTransport(private val result: Any) : MtProtoRpcTransport {
        lateinit var method: TlMethod<*>

        override suspend fun <R> execute(method: TlMethod<R>): R {
            this.method = method
            @Suppress("UNCHECKED_CAST")
            return result as R
        }

        override fun close() = Unit
    }

    private class RecordingSearchMessageStore : MtProtoMessageProjectionStore by NoOpMtProtoMessageProjectionStore {
        private val staged = mutableListOf<MtProtoMessageReadModel>()

        override suspend fun stageMessages(scope: MtProtoAuthKeyScope, messages: List<org.monogram.mtproto.tl.generated.cloud.layer223.Message_73e57f95e4>, isScheduled: Boolean) {
            staged += messages.filterIsInstance<MessageEmpty>().map {
                MtProtoMessageReadModel(
                    peerType = MtProtoMessagePeerType.USER,
                    peerId = (it.peerId as PeerUser).userId,
                    messageId = it.id,
                    senderType = null,
                    senderId = null,
                    date = it.id,
                    text = "",
                    isService = false,
                    isDeleted = true,
                    isOutgoing = false,
                    isMentioned = false,
                    isMediaUnread = false,
                    isSilent = false,
                    isPinned = false,
                    editDate = null,
                    groupedId = null,
                    hasMedia = false,
                )
            }
        }

        override suspend fun get(scope: MtProtoAuthKeyScope, peerType: MtProtoMessagePeerType, peerId: Long, messageId: Int) =
            staged.firstOrNull { it.peerType == peerType && it.peerId == peerId && it.messageId == messageId }
    }

    private class FakeMessageStore(private val messages: List<MtProtoMessageReadModel>) : MtProtoMessageProjectionStore by NoOpMtProtoMessageProjectionStore {        override suspend fun search(scope: MtProtoAuthKeyScope, query: String, limit: Int, offset: Int) =
            messages.filter { it.text.orEmpty().contains(query, ignoreCase = true) }.drop(offset).take(limit)
    }

    private class FakeDialogRepository : DialogSnapshotRepository {
        override suspend fun getDialogs(accountId: String) = listOf(
            DialogSnapshotModel(
                peerId = 42,
                peerType = DialogPeerType.PRIVATE,
                title = "Alice",
                username = "alice_user",
                isPeerResolved = true,
                isPeerDeleted = false,
                isPeerForbidden = false,
                latestMessage = DialogMessagePreviewModel(7, 42, 100, "hello", false, false, false, false),
                unreadCount = 4,
            ),
            DialogSnapshotModel(
                peerId = 43,
                peerType = DialogPeerType.PRIVATE,
                title = "Bob",
                username = "bob_user",
                isPeerResolved = true,
                isPeerDeleted = false,
                isPeerForbidden = false,
                latestMessage = DialogMessagePreviewModel(8, 43, 101, "world", false, false, false, false),
            ),
        )
    }
}
