package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.StoryItemDeleted
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerStories_9de86f4fe6
import org.monogram.mtproto.tl.generated.cloud.layer223.StoriesStealthMode_9a2f11feb7
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateReadStories
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateStoriesStealthMode
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateStory
import org.monogram.mtproto.tl.generated.cloud.layer223.Updates_02c952992b
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.AllStories_75ae93d8cd

class MtProtoStoryResultStagerTest {
    private val scope = MtProtoAuthKeyScope("slot_a", MtProtoEnvironment.PRODUCTION, 2)

    @Test
    fun `stages live story tombstone with its authoritative peer`() = runBlocking {
        val stories = RecordingStories()
        val stager = MtProtoStoryResultStager(stories)

        stager.stageLive(
            scope,
            Updates_02c952992b(
                updates = listOf(UpdateStory(PeerUser(9), StoryItemDeleted(7))),
                users = emptyList(),
                chats = emptyList(),
                date = 1,
                seq = 1,
            ),
        )

        val stored = stories.staged.single()
        assertEquals(MtProtoStoryKey("USER", 9, 7), stored.key)
        assertTrue(stored.isDeleted)
        assertTrue(stored.payload.isNotEmpty())
    }

    @Test
    fun `stages live stealth mode without inventing dates`() = runBlocking {
        val stealthModes = RecordingStealthModes()
        val stager = MtProtoStoryResultStager(RecordingStories(), stealthModes = stealthModes)

        stager.stageLive(
            scope,
            Updates_02c952992b(
                updates = listOf(UpdateStoriesStealthMode(StoriesStealthMode_9a2f11feb7(100, 200))),
                users = emptyList(),
                chats = emptyList(),
                date = 1,
                seq = 1,
            ),
        )

        assertEquals(MtProtoStoryStealthMode(100, 200), stealthModes.saved.single())
    }

    @Test
    fun `appends a continuation page without discarding earlier stories`() = runBlocking {
        val stories = RecordingStories()
        val stager = MtProtoStoryResultStager(stories)

        stager.stageAllStories(
            scope,
            "MAIN",
            allStories("first", PeerStories_9de86f4fe6(PeerUser(9), 1, listOf(StoryItemDeleted(7)))),
        )
        stager.stageAllStories(
            scope,
            "MAIN",
            allStories("second", PeerStories_9de86f4fe6(PeerUser(10), 2, listOf(StoryItemDeleted(8)))),
            append = true,
        )

        assertEquals(
            listOf(MtProtoStoryKey("USER", 9, 7), MtProtoStoryKey("USER", 10, 8)),
            stories.active.getValue("MAIN").map { it.key },
        )
        assertTrue(stories.active.getValue("MAIN")[0].orderKey > stories.active.getValue("MAIN")[1].orderKey)
        assertEquals("second", stories.cursors.getValue("MAIN").state)
    }

    @Test
    fun `stages live read marker only for the authoritative peer`() = runBlocking {
        val stories = RecordingStories()
        val stager = MtProtoStoryResultStager(stories)

        stager.stageLive(
            scope,
            Updates_02c952992b(
                updates = listOf(UpdateReadStories(PeerUser(9), 7)),
                users = emptyList(),
                chats = emptyList(),
                date = 1,
                seq = 1,
            ),
        )

        assertEquals(listOf(Triple("USER", 9L, 7)), stories.readMarkers)
    }

    private fun allStories(state: String, peerStories: PeerStories_9de86f4fe6) = AllStories_75ae93d8cd(
        hasMore = false,
        count = 1,
        state = state,
        peerStories = listOf(peerStories),
        chats = emptyList(),
        users = emptyList(),
        stealthMode = StoriesStealthMode_9a2f11feb7(null, null),
    )

    private class RecordingStealthModes : MtProtoStoryStealthModeStore by NoOpMtProtoStoryStealthModeStore {
        val saved = mutableListOf<MtProtoStoryStealthMode>()

        override suspend fun save(scope: MtProtoAuthKeyScope, mode: org.monogram.mtproto.tl.generated.cloud.layer223.StoriesStealthMode_074c681db4) {
            val supported = mode as StoriesStealthMode_9a2f11feb7
            saved += MtProtoStoryStealthMode(supported.activeUntilDate ?: 0, supported.cooldownUntilDate ?: 0)
        }
    }

    private class RecordingStories : MtProtoStoryProjectionStore by NoOpMtProtoStoryProjectionStore {
        val staged = mutableListOf<MtProtoStoryPayload>()
        val readMarkers = mutableListOf<Triple<String, Long, Int>>()
        val active = mutableMapOf<String, List<MtProtoStoryActiveListItem>>()
        val cursors = mutableMapOf<String, MtProtoStoryListCursor>()

        override suspend fun upsert(scope: MtProtoAuthKeyScope, story: MtProtoStoryPayload) {
            staged += story
        }

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

        override suspend fun updateMaxReadStoryId(scope: MtProtoAuthKeyScope, peerType: String, peerId: Long, maxReadStoryId: Int) {
            readMarkers += Triple(peerType, peerId, maxReadStoryId)
        }
    }
}
