package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.StoriesStealthMode_9a2f11feb7
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.AllStoriesNotModified
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.AllStories_75ae93d8cd
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.GetAllStories
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoStoryRefreshRepositoryTest {
    @Test
    fun `refreshes main and archive with distinct hidden flags`() = runBlocking {
        val transport = RecordingTransport(
            listOf(
                AllStories_75ae93d8cd(false, 0, "main-1", emptyList(), emptyList(), emptyList(), stealth()),
                AllStories_75ae93d8cd(false, 0, "archive-1", emptyList(), emptyList(), emptyList(), stealth()),
            )
        )
        val repository = repository(transport)

        repository.refreshInitialLists()

        assertEquals(listOf(false, true), transport.requests.map { it.hidden })
        assertEquals(listOf(null, null), transport.requests.map { it.state })
        assertTrue(transport.closed)
    }

    @Test
    fun `not modified response preserves projections and advances its cursor`() = runBlocking {
        val transport = RecordingTransport(
            listOf(
                AllStories_75ae93d8cd(false, 0, "main-1", emptyList(), emptyList(), emptyList(), stealth()),
                AllStories_75ae93d8cd(false, 0, "archive-1", emptyList(), emptyList(), emptyList(), stealth()),
                AllStoriesNotModified("main-2", stealth()),
                AllStoriesNotModified("archive-2", stealth()),
            )
        )
        val stories = RecordingStories()
        val repository = repository(transport, stories)

        repository.refreshInitialLists()
        repository.refreshInitialLists()

        assertEquals(listOf("main-2", "archive-2"), listOf(
            stories.cursors["MAIN"]?.state,
            stories.cursors["ARCHIVE"]?.state,
        ))
        assertEquals(listOf("main-1", "archive-1"), transport.requests.drop(2).map { it.state })
    }

    private fun repository(
        transport: RecordingTransport,
        stories: RecordingStories = transport.stories,
    ) = MtProtoStoryRefreshRepositoryImpl(
        configSource = TelegramMtProtoBootstrapConfigSource { config() },
        transportFactory = MtProtoSessionTransportFactory { transport },
        stories = stories,
        resultStager = MtProtoStoryResultStager(stories),
    )

    private fun config() = TelegramMtProtoBootstrapConfig(
        endpoint = TelegramMtProtoEndpoint(2, "dc", 443),
        handshake = MtProtoHandshakeConfig(2, listOf("key")),
        cloud = CloudLayer223ConnectionConfig(1, "device", "system", "app", "en"),
    )

    private fun stealth() = StoriesStealthMode_9a2f11feb7(null, null)

    private class RecordingTransport(
        private val results: List<Any>,
    ) : MtProtoRpcTransport {
        val requests = mutableListOf<GetAllStories>()
        val stories = RecordingStories()
        var closed = false
        private var index = 0

        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R {
            requests += method as GetAllStories
            return results[index++] as R
        }

        override fun close() {
            closed = true
        }
    }

    private class RecordingStories : MtProtoStoryProjectionStore by NoOpMtProtoStoryProjectionStore {
        val cursors = mutableMapOf<String, MtProtoStoryListCursor>()
        val active = mutableMapOf<String, List<MtProtoStoryActiveListItem>>()

        override suspend fun replaceActiveList(
            scope: MtProtoAuthKeyScope,
            listType: String,
            stories: List<MtProtoStoryActiveListItem>,
            cursor: MtProtoStoryListCursor,
        ) {
            active[listType] = stories
            cursors[listType] = cursor
        }

        override suspend fun activeList(scope: MtProtoAuthKeyScope, listType: String) = active[listType].orEmpty()
        override suspend fun cursor(scope: MtProtoAuthKeyScope, listType: String) = cursors[listType]
    }
}
