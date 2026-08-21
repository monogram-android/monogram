package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.stories.StoryListType
import org.monogram.domain.models.stories.StoryReactionModel
import org.monogram.domain.models.stories.StoryPostCapabilityModel
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.codec.CloudTlObjectCodec
import org.monogram.mtproto.tl.generated.cloud.layer223.StoryItemDeleted
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesTooLong
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.CanSendStory
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.DeleteStories
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.CanSendStoryCount_11d73fe4aa
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.ReadStories
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.SendReaction
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.TogglePeerStoriesHidden
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoStoryListRepositoryTest {
    @Test
    fun `maps main and archive lists to authoritative hidden values`() = runBlocking {
        val transport = RecordingTransport()
        val repository = repository(transport)

        repository.setActiveStoriesList(-42, StoryListType.MAIN)
        repository.setActiveStoriesList(-42, StoryListType.ARCHIVE)

        assertEquals(listOf(false, true), transport.requests.map { it.hidden })
        assertEquals(listOf(42L, 42L), transport.requests.map { (it.peer as org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat).chatId })
    }

    @Test
    fun `marks stories read and persists the acknowledged peer cursor`() = runBlocking {
        val transport = ReadRecordingTransport()
        val stories = RecordingStories()
        val repository = MtProtoStoryListRepositoryImpl(
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = NoOpMtProtoUserProjectionStore,
            chats = NoOpMtProtoChatProjectionStore,
            stories = stories,
        )

        repository.markRead(-42, 7)

        assertEquals(ReadStories(org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat(42), 7), transport.request)
        assertEquals(Triple("GROUP", 42L, 7), stories.readMarker)
        assertEquals(true, transport.closed)
    }

    @Test
    fun `maps positive and exhausted active story slots`() = runBlocking {
        val allowedTransport = CapabilityRecordingTransport(3)
        val allowedRepository = capabilityRepository(allowedTransport)
        assertEquals(StoryPostCapabilityModel.Allowed(3), allowedRepository.canSend(-42))
        assertEquals(CanSendStory(org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat(42)), allowedTransport.request)
        assertEquals(true, allowedTransport.closed)

        val exhaustedTransport = CapabilityRecordingTransport(0)
        assertEquals(StoryPostCapabilityModel.ActiveStoryLimitExceeded, capabilityRepository(exhaustedTransport).canSend(-42))
        assertEquals(true, exhaustedTransport.closed)
    }

    @Test
    fun `persists a tombstone only after the server confirms story deletion`() = runBlocking {
        val transport = DeleteRecordingTransport(listOf(7))
        val stories = DeletionRecordingStories()
        val repository = MtProtoStoryListRepositoryImpl(
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = NoOpMtProtoUserProjectionStore,
            chats = NoOpMtProtoChatProjectionStore,
            stories = stories,
        )

        assertEquals(true, repository.delete(-42, 7))
        assertEquals(DeleteStories(org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat(42), listOf(7)), transport.request)
        assertEquals(MtProtoStoryKey("GROUP", 42, 7), stories.tombstone?.key)
        assertEquals(true, stories.tombstone?.isDeleted)
        assertTrue(CloudTlObjectCodec.decode(stories.tombstone!!.payload) is StoryItemDeleted)
        assertEquals(true, transport.closed)

        val rejected = DeleteRecordingTransport(emptyList())
        assertEquals(false, repository(rejected).delete(-42, 7))
    }

    @Test
    fun `maps complete story reaction variants and stages updates`() = runBlocking {
        val transport = ReactionRecordingTransport()
        val stager = RecordingStager()
        val repository = MtProtoStoryListRepositoryImpl(
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = NoOpMtProtoUserProjectionStore,
            chats = NoOpMtProtoChatProjectionStore,
            cloudObjectStager = stager,
        )

        repository.setReaction(-42, 7, StoryReactionModel())
        repository.setReaction(-42, 7, StoryReactionModel(emoji = "👍"))
        repository.setReaction(-42, 7, StoryReactionModel(customEmojiId = 99))
        repository.setReaction(-42, 7, StoryReactionModel(isPaid = true))

        assertEquals(
            listOf(
                org.monogram.mtproto.tl.generated.cloud.layer223.ReactionEmpty,
                org.monogram.mtproto.tl.generated.cloud.layer223.ReactionEmoji("👍"),
                org.monogram.mtproto.tl.generated.cloud.layer223.ReactionCustomEmoji(99),
                org.monogram.mtproto.tl.generated.cloud.layer223.ReactionPaid,
            ),
            transport.requests.map(SendReaction::reaction),
        )
        assertEquals(listOf(false, true, true, false), transport.requests.map(SendReaction::addToRecent))
        assertEquals(4, stager.calls)
        assertEquals(true, transport.closed)
    }

    @Test
    fun `rejects conflicting paid reaction selectors before opening transport`() = runBlocking {
        var opened = false
        val repository = MtProtoStoryListRepositoryImpl(
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = MtProtoSessionTransportFactory { opened = true; error("transport must not open") },
            users = NoOpMtProtoUserProjectionStore,
            chats = NoOpMtProtoChatProjectionStore,
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.setReaction(-42, 7, StoryReactionModel(emoji = "👍", isPaid = true)) }
        }
        assertEquals(false, opened)
    }

    @Test
    fun `returns server rejection and still closes transport`() = runBlocking {
        val transport = RecordingTransport(result = false)
        val repository = repository(transport)

        assertEquals(false, repository.setActiveStoriesList(-42, StoryListType.ARCHIVE))
        assertEquals(true, transport.closed)
    }

    @Test
    fun `rejects list removal before opening transport`() = runBlocking {
        var opened = false
        val repository = MtProtoStoryListRepositoryImpl(
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = MtProtoSessionTransportFactory { opened = true; error("transport must not open") },
            users = NoOpMtProtoUserProjectionStore,
            chats = NoOpMtProtoChatProjectionStore,
        )

        assertThrows(UnsupportedOperationException::class.java) {
            runBlocking { repository.setActiveStoriesList(-42, null) }
        }
        assertEquals(false, opened)
    }

    private fun repository(transport: DeleteRecordingTransport) = MtProtoStoryListRepositoryImpl(
        configSource = TelegramMtProtoBootstrapConfigSource { config() },
        transportFactory = MtProtoSessionTransportFactory { transport },
        users = NoOpMtProtoUserProjectionStore,
        chats = NoOpMtProtoChatProjectionStore,
    )

    private fun capabilityRepository(transport: CapabilityRecordingTransport) = MtProtoStoryListRepositoryImpl(
        configSource = TelegramMtProtoBootstrapConfigSource { config() },
        transportFactory = MtProtoSessionTransportFactory { transport },
        users = NoOpMtProtoUserProjectionStore,
        chats = NoOpMtProtoChatProjectionStore,
    )

    private fun repository(transport: RecordingTransport) = MtProtoStoryListRepositoryImpl(
        configSource = TelegramMtProtoBootstrapConfigSource { config() },
        transportFactory = MtProtoSessionTransportFactory { transport },
        users = NoOpMtProtoUserProjectionStore,
        chats = NoOpMtProtoChatProjectionStore,
    )

    private fun config() = TelegramMtProtoBootstrapConfig(
        endpoint = TelegramMtProtoEndpoint(2, "dc", 443),
        handshake = MtProtoHandshakeConfig(2, listOf("key")),
        cloud = CloudLayer223ConnectionConfig(1, "device", "system", "app", "en"),
    )

    private class RecordingStories : MtProtoStoryProjectionStore by NoOpMtProtoStoryProjectionStore {
        var readMarker: Triple<String, Long, Int>? = null
        override suspend fun updateMaxReadStoryId(scope: MtProtoAuthKeyScope, peerType: String, peerId: Long, maxReadStoryId: Int) {
            readMarker = Triple(peerType, peerId, maxReadStoryId)
        }
    }

    private class ReadRecordingTransport : MtProtoRpcTransport {
        lateinit var request: ReadStories
        var closed = false
        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R {
            request = method as ReadStories
            return emptyList<Int>() as R
        }
        override fun close() { closed = true }
    }

    private class DeletionRecordingStories : MtProtoStoryProjectionStore by NoOpMtProtoStoryProjectionStore {
        var tombstone: MtProtoStoryPayload? = null
        override suspend fun upsert(scope: MtProtoAuthKeyScope, story: MtProtoStoryPayload) {
            tombstone = story
        }
    }

    private class DeleteRecordingTransport(private val deleted: List<Int>) : MtProtoRpcTransport {
        lateinit var request: DeleteStories
        var closed = false
        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R {
            request = method as DeleteStories
            return deleted as R
        }
        override fun close() { closed = true }
    }

    private class CapabilityRecordingTransport(private val remaining: Int) : MtProtoRpcTransport {
        lateinit var request: CanSendStory
        var closed = false
        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R {
            request = method as CanSendStory
            return CanSendStoryCount_11d73fe4aa(remaining) as R
        }
        override fun close() { closed = true }
    }

    private class RecordingStager : MtProtoCloudObjectStager by NoOpMtProtoCloudObjectStager {
        var calls = 0
        override suspend fun stageLive(scope: MtProtoAuthKeyScope, envelope: org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5) {
            calls++
        }
    }

    private class ReactionRecordingTransport : MtProtoRpcTransport {
        val requests = mutableListOf<SendReaction>()
        var closed = false
        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R {
            requests += method as SendReaction
            return UpdatesTooLong as R
        }
        override fun close() { closed = true }
    }

    private class RecordingTransport(
        private val result: Boolean = true,
    ) : MtProtoRpcTransport {
        val requests = mutableListOf<TogglePeerStoriesHidden>()
        var closed = false

        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R {
            requests += method as TogglePeerStoriesHidden
            return result as R
        }

        override fun close() { closed = true }
    }
}
