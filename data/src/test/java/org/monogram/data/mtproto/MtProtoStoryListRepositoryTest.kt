package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.domain.models.stories.StoryListType
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.ReadStories
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
