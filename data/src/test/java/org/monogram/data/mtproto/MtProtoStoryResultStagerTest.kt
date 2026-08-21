package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.StoryItemDeleted
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateReadStories
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateStory
import org.monogram.mtproto.tl.generated.cloud.layer223.Updates_02c952992b

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

    private class RecordingStories : MtProtoStoryProjectionStore by NoOpMtProtoStoryProjectionStore {
        val staged = mutableListOf<MtProtoStoryPayload>()
        val readMarkers = mutableListOf<Triple<String, Long, Int>>()

        override suspend fun upsert(scope: MtProtoAuthKeyScope, story: MtProtoStoryPayload) {
            staged += story
        }

        override suspend fun updateMaxReadStoryId(scope: MtProtoAuthKeyScope, peerType: String, peerId: Long, maxReadStoryId: Int) {
            readMarkers += Triple(peerType, peerId, maxReadStoryId)
        }
    }
}
