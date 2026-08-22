package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.repository.ForwardOptions
import org.monogram.domain.repository.ForwardRequest
import org.monogram.domain.repository.ForwardTarget
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesTooLong
import org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.AffectedHistory_608e824b28
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.DeleteHistory
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.EditMessage
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.ForwardMessages
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.ReadMentions
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.ReadReactions
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.SendMessage
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.SendScheduledMessages
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.SendReaction
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.UpdatePinnedMessage
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageEntityType
import org.monogram.data.mtproto.MtProtoAuthKeyScope
import org.monogram.data.mtproto.MtProtoEnvironment
import org.monogram.data.mtproto.NoOpMtProtoUserProjectionStore
import org.monogram.data.mtproto.MtProtoUserProjectionStore

class MtProtoTextMessageRepositoryImplTest {
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
        assertTrue(!request.noWebpage)
        assertEquals(InputPeerUser(7L, 70L), request.peer)
        assertEquals(1, messageStore.staged.size)
        assertTrue(transport.closed)
    }

    @Test
    fun `sends text with reply and topic context`() = runBlocking {
        val transport = RecordingTransport()
        val repository = MtProtoTextMessageRepositoryImpl(
            configSource = configSource(),
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = FakeUserStore(MtProtoUserReadModel(7L, 70L, null, null, null, null, false, false, false, false, false, false, false, false, false, false, false)),
            chats = NoOpMtProtoChatProjectionStore,
            messages = NoOpMtProtoMessageProjectionStore,
        )

        repository.sendText(
            chatId = 7L,
            peerType = DialogPeerType.PRIVATE,
            text = "reply",
            silent = false,
            scheduleDate = null,
            disableLinkPreview = false,
            replyToMessageId = 3L,
            threadId = 5L,
        )

        val reply = (transport.method as SendMessage).replyTo as org.monogram.mtproto.tl.generated.cloud.layer223.InputReplyToMessage
        assertEquals(3, reply.replyToMsgId)
        assertEquals(5, reply.topMsgId)
        assertTrue(transport.closed)
    }

    @Test
    fun `sends supported rich text entities through owned transport`() = runBlocking {
        val transport = RecordingTransport()
        val repository = MtProtoTextMessageRepositoryImpl(
            configSource = configSource(),
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = FakeUserStore(MtProtoUserReadModel(7L, 70L, null, null, null, null, false, false, false, false, false, false, false, false, false, false, false)),
            chats = NoOpMtProtoChatProjectionStore,
            messages = NoOpMtProtoMessageProjectionStore,
        )

        repository.sendText(
            chatId = 7L,
            peerType = DialogPeerType.PRIVATE,
            text = "bold link",
            silent = false,
            scheduleDate = null,
            disableLinkPreview = false,
            replyToMessageId = null,
            threadId = null,
            entities = listOf(
                org.monogram.domain.models.MessageEntity(0, 4, org.monogram.domain.models.MessageEntityType.Bold),
                org.monogram.domain.models.MessageEntity(5, 4, org.monogram.domain.models.MessageEntityType.TextUrl("https://example.com")),
            ),
        )

        val entities = (transport.method as SendMessage).entities!!
        assertEquals(org.monogram.mtproto.tl.generated.cloud.layer223.MessageEntityBold(0, 4), entities[0])
        assertEquals(
            org.monogram.mtproto.tl.generated.cloud.layer223.MessageEntityTextUrl(5, 4, "https://example.com"),
            entities[1],
        )
        assertTrue(transport.closed)
    }

    @Test
    fun `drops unsupported entity types without crashing`() = runBlocking {
        val scope = MtProtoAuthKeyScope("test", MtProtoEnvironment.PRODUCTION, 2)
        val users = NoOpMtProtoUserProjectionStore

        // MediaTimestamp maps to null.
        val tsEntity = MessageEntity(0, 5, MessageEntityType.MediaTimestamp(1))
        assertNull(tsEntity.toMtProtoEntity(scope, "timestamp", users))

        // Other maps to null.
        val otherEntity = MessageEntity(0, 5, MessageEntityType.Other("custom"))
        assertNull(otherEntity.toMtProtoEntity(scope, "custom", users))

        // Supported types still map.
        val hashtag = MessageEntity(0, 4, MessageEntityType.Hashtag)
        assertTrue(hashtag.toMtProtoEntity(scope, "#tag", users) is org.monogram.mtproto.tl.generated.cloud.layer223.MessageEntityHashtag)
    }

    @Test
    fun `sends typing action with topic id`() = runBlocking {
        val transport = RecordingTransport()
        val repository = MtProtoTextMessageRepositoryImpl(
            configSource = configSource(),
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = FakeUserStore(MtProtoUserReadModel(7L, 70L, null, null, null, null, false, false, false, false, false, false, false, false, false, false, false)),
            chats = NoOpMtProtoChatProjectionStore,
            messages = NoOpMtProtoMessageProjectionStore,
        )

        repository.sendTyping(7L, DialogPeerType.PRIVATE, threadId = 12L)

        val request = transport.method as org.monogram.mtproto.tl.generated.cloud.layer223.messages.SetTyping
        assertEquals(InputPeerUser(7L, 70L), request.peer)
        assertEquals(12, request.topMsgId)
        assertEquals(org.monogram.mtproto.tl.generated.cloud.layer223.SendMessageTypingAction, request.action)
        assertTrue(transport.closed)
    }

    @Test
    fun `sends scheduled silent text and stages returned updates`() = runBlocking {
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

        repository.sendText(
            7L,
            DialogPeerType.PRIVATE,
            "later",
            silent = true,
            scheduleDate = 1_700_000_000,
            disableLinkPreview = true,
        )

        val request = transport.method as SendMessage
        assertEquals("later", request.message)
        assertTrue(request.silent)
        assertTrue(request.noWebpage)
        assertEquals(1_700_000_000, request.scheduleDate)
        assertEquals(1, messageStore.staged.size)
        assertTrue(transport.closed)
    }

    @Test
    fun `marks projected mentions and reactions read through owned transport`() = runBlocking {
        val mentionTransport = RecordingTransport()
        val reactionTransport = RecordingTransport()
        fun repository(transport: RecordingTransport) = MtProtoTextMessageRepositoryImpl(
            configSource = configSource(),
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = FakeUserStore(MtProtoUserReadModel(7L, 70L, null, null, null, null, false, false, false, false, false, false, false, false, false, false, false)),
            chats = NoOpMtProtoChatProjectionStore,
            messages = NoOpMtProtoMessageProjectionStore,
        )

        repository(mentionTransport).markMentionsRead(7L, DialogPeerType.PRIVATE)
        repository(reactionTransport).markReactionsRead(7L, DialogPeerType.PRIVATE)

        val mentions = mentionTransport.method as ReadMentions
        assertEquals(InputPeerUser(7L, 70L), mentions.peer)
        assertEquals(null, mentions.topMsgId)
        val reactions = reactionTransport.method as ReadReactions
        assertEquals(InputPeerUser(7L, 70L), reactions.peer)
        assertEquals(null, reactions.topMsgId)
        assertEquals(null, reactions.savedPeerId)
        assertTrue(mentionTransport.closed)
        assertTrue(reactionTransport.closed)
    }

    @Test
    fun `clears projected chat history with requested revoke semantics`() = runBlocking {
        val transport = RecordingTransport()
        val repository = MtProtoTextMessageRepositoryImpl(
            configSource = configSource(),
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = FakeUserStore(MtProtoUserReadModel(7L, 70L, null, null, null, null, false, false, false, false, false, false, false, false, false, false, false)),
            chats = NoOpMtProtoChatProjectionStore,
            messages = NoOpMtProtoMessageProjectionStore,
        )

        repository.clearHistory(7L, DialogPeerType.PRIVATE, revoke = true)

        val request = transport.method as DeleteHistory
        assertEquals(InputPeerUser(7L, 70L), request.peer)
        assertTrue(!request.justClear)
        assertTrue(request.revoke)
        assertEquals(0, request.maxId)
        assertTrue(transport.closed)
    }

    @Test
    fun `sends scheduled message now and stages returned updates`() = runBlocking {
        val transport = RecordingTransport()
        val messageStore = RecordingMessageStore()
        val repository = MtProtoTextMessageRepositoryImpl(
            configSource = configSource(),
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = FakeUserStore(MtProtoUserReadModel(7L, 70L, null, null, null, null, false, false, false, false, false, false, false, false, false, false, false)),
            chats = NoOpMtProtoChatProjectionStore,
            messages = messageStore,
        )

        repository.sendScheduledNow(7L, DialogPeerType.PRIVATE, 8L)

        val request = transport.method as SendScheduledMessages
        assertEquals(InputPeerUser(7L, 70L), request.peer)
        assertEquals(listOf(8), request.id)
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
    fun `forwards messages to each target with copy options`() = runBlocking {
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

        repository.forwardMessages(
            ForwardRequest(
                fromChatId = 7L,
                messageIds = listOf(8L, 9L),
                targets = listOf(ForwardTarget(7L), ForwardTarget(-11L)),
                options = ForwardOptions(sendCopy = true, removeCaption = true),
            ),
        )

        assertEquals(2, transport.methods.size)
        val first = transport.methods[0] as ForwardMessages
        val second = transport.methods[1] as ForwardMessages
        assertEquals(InputPeerUser(7L, 70L), first.fromPeer)
        assertEquals(InputPeerUser(7L, 70L), first.toPeer)
        assertEquals(InputPeerChat(11L), second.toPeer)
        assertTrue(first.dropAuthor)
        assertTrue(first.dropMediaCaptions)
        assertEquals(listOf(8, 9), first.id)
        assertEquals(2, messageStore.staged.size)
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
    fun `sets custom emoji reaction and stages returned updates`() = runBlocking {
        val transport = RecordingTransport()
        val repository = MtProtoTextMessageRepositoryImpl(
            configSource = configSource(),
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = FakeUserStore(MtProtoUserReadModel(7L, 70L, null, null, null, null, false, false, false, false, false, false, false, false, false, false, false)),
            chats = NoOpMtProtoChatProjectionStore,
            messages = RecordingMessageStore(),
        )

        repository.setEmojiReaction(7L, DialogPeerType.PRIVATE, 8L, "123")

        val request = transport.method as SendReaction
        assertEquals(123L, (request.reaction!!.single() as org.monogram.mtproto.tl.generated.cloud.layer223.ReactionCustomEmoji).documentId)
        assertTrue(transport.closed)
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

        repository.editText(
            chatId = 7L,
            peerType = DialogPeerType.PRIVATE,
            messageId = 8L,
            text = "edited",
            entities = listOf(
                org.monogram.domain.models.MessageEntity(
                    offset = 0,
                    length = 6,
                    type = org.monogram.domain.models.MessageEntityType.Underline,
                ),
            ),
        )

        val request = transport.method as EditMessage
        assertEquals(8, request.id)
        assertEquals("edited", request.message)
        assertEquals(
            listOf(org.monogram.mtproto.tl.generated.cloud.layer223.MessageEntityUnderline(0, 6)),
            request.entities,
        )
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
        val methods = mutableListOf<TlMethod<*>>()
        var closed = false

        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R {
            this.method = method
            methods += method
            return when {
                method is DeleteHistory -> AffectedHistory_608e824b28(0, 0, 0) as R
                method is org.monogram.mtproto.tl.generated.cloud.layer223.messages.SetTyping -> true as R
                else -> UpdatesTooLong as R
            }
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
