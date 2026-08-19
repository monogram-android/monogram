package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.DialogPeerType
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesTooLong
import org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.EditMessage
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.ForwardMessages
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.SendMessage
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.SendReaction
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.UpdatePinnedMessage
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoTextMessageRepositoryImplTest {
    @Test
    fun `sends plain text to projected user and stages returned updates`() = runBlocking {
        val transport = RecordingTransport()
        val messageStore = RecordingMessageStore()
        val repository = MtProtoTextMessageRepositoryImpl(
            configSource = configSource(),
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = FakeUserStore(MtProtoUserReadModel(7L, 70L, null, null, null, null, false, false, false, false, false, false, false, false, false, false, false)),
            chats = NoOpMtProtoChatProjectionStore,
            messages = messageStore,
            randomId = { 99L },
        )

        repository.sendText(7L, DialogPeerType.PRIVATE, "hello")

        val request = transport.method as SendMessage
        assertEquals("hello", request.message)
        assertEquals(99L, request.randomId)
        assertEquals(InputPeerUser(7L, 70L), request.peer)
        assertEquals(1, messageStore.staged.size)
        assertTrue(transport.closed)
    }

    @Test
    fun `forwards message to its projected chat and stages returned updates`() = runBlocking {
        val transport = RecordingTransport()
        val messageStore = RecordingMessageStore()
        val repository = MtProtoTextMessageRepositoryImpl(
            configSource = configSource(),
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = FakeUserStore(MtProtoUserReadModel(7L, 70L, null, null, null, null, false, false, false, false, false, false, false, false, false, false, false)),
            chats = NoOpMtProtoChatProjectionStore,
            messages = messageStore,
            randomId = { 99L },
        )

        repository.forwardToSelf(7L, DialogPeerType.PRIVATE, 8L)

        val request = transport.method as ForwardMessages
        assertEquals(InputPeerUser(7L, 70L), request.fromPeer)
        assertEquals(request.fromPeer, request.toPeer)
        assertEquals(listOf(8), request.id)
        assertEquals(listOf(99L), request.randomId)
        assertTrue(request.dropAuthor)
        assertEquals(1, messageStore.staged.size)
        assertTrue(transport.closed)
    }

    @Test
    fun `pins message and stages returned updates`() = runBlocking {
        val transport = RecordingTransport()
        val messageStore = RecordingMessageStore()
        val repository = MtProtoTextMessageRepositoryImpl(
            configSource = configSource(),
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = FakeUserStore(MtProtoUserReadModel(7L, 70L, null, null, null, null, false, false, false, false, false, false, false, false, false, false, false)),
            chats = NoOpMtProtoChatProjectionStore,
            messages = messageStore,
        )

        repository.setPinned(7L, DialogPeerType.PRIVATE, 8L, pinned = true)

        val request = transport.method as UpdatePinnedMessage
        assertEquals(8, request.id)
        assertEquals(false, request.unpin)
        assertEquals(1, messageStore.staged.size)
    }

    @Test
    fun `sets emoji reaction and stages returned updates`() = runBlocking {
        val transport = RecordingTransport()
        val messageStore = RecordingMessageStore()
        val repository = MtProtoTextMessageRepositoryImpl(
            configSource = configSource(),
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = FakeUserStore(MtProtoUserReadModel(7L, 70L, null, null, null, null, false, false, false, false, false, false, false, false, false, false, false)),
            chats = NoOpMtProtoChatProjectionStore,
            messages = messageStore,
        )

        repository.setEmojiReaction(7L, DialogPeerType.PRIVATE, 8L, "👍")

        val request = transport.method as SendReaction
        assertEquals(8, request.msgId)
        assertEquals("👍", (request.reaction!!.single() as org.monogram.mtproto.tl.generated.cloud.layer223.ReactionEmoji).emoticon)
        assertEquals(1, messageStore.staged.size)
    }

    @Test
    fun `edits plain text and stages returned updates`() = runBlocking {
        val transport = RecordingTransport()
        val messageStore = RecordingMessageStore()
        val repository = MtProtoTextMessageRepositoryImpl(
            configSource = configSource(),
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = FakeUserStore(MtProtoUserReadModel(7L, 70L, null, null, null, null, false, false, false, false, false, false, false, false, false, false, false)),
            chats = NoOpMtProtoChatProjectionStore,
            messages = messageStore,
        )

        repository.editText(7L, DialogPeerType.PRIVATE, 8L, "edited")

        val request = transport.method as EditMessage
        assertEquals(8, request.id)
        assertEquals("edited", request.message)
        assertEquals(1, messageStore.staged.size)
        assertTrue(transport.closed)
    }

    @Test
    fun `decodes encoded basic-group chat id before sending`() = runBlocking {
        val transport = RecordingTransport()
        val repository = MtProtoTextMessageRepositoryImpl(
            configSource = configSource(),
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = NoOpMtProtoUserProjectionStore,
            chats = NoOpMtProtoChatProjectionStore,
            messages = NoOpMtProtoMessageProjectionStore,
            randomId = { 99L },
        )

        repository.sendText(-7L, DialogPeerType.BASIC_GROUP, "hello")

        assertEquals(InputPeerChat(7L), (transport.method as SendMessage).peer)
    }

    private fun configSource() = TelegramMtProtoBootstrapConfigSource {
        TelegramMtProtoBootstrapConfig(
            endpoint = TelegramMtProtoEndpoint(2, "dc", 443),
            handshake = MtProtoHandshakeConfig(2, listOf("key")),
            cloud = CloudLayer223ConnectionConfig(1, "test", "test", "test", "en"),
        )
    }

    private class RecordingTransport : MtProtoRpcTransport {
        lateinit var method: TlMethod<*>
        var closed = false

        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R {
            this.method = method
            return UpdatesTooLong as R
        }

        override fun close() { closed = true }
    }

    private class FakeUserStore(private val user: MtProtoUserReadModel) : MtProtoUserProjectionStore {
        override suspend fun upsert(scope: MtProtoAuthKeyScope, users: List<org.monogram.mtproto.tl.generated.cloud.layer223.User_655b5dfc57>) = Unit
        override suspend fun get(scope: MtProtoAuthKeyScope, userId: Long) = user.takeIf { it.userId == userId }
        override suspend fun getSelf(scope: MtProtoAuthKeyScope) = null
        override suspend fun getAll(scope: MtProtoAuthKeyScope) = listOf(user)
        override suspend fun backfill(scope: MtProtoAuthKeyScope) = MtProtoUserProjectionBackfillResult(0, 0)
        override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) = Unit
    }

    private class RecordingMessageStore : MtProtoMessageProjectionStore by NoOpMtProtoMessageProjectionStore {
        val staged = mutableListOf<Updates_faf6aaa3d5>()
        override suspend fun stageLive(scope: MtProtoAuthKeyScope, envelope: Updates_faf6aaa3d5) {
            staged += envelope
        }
    }
}
