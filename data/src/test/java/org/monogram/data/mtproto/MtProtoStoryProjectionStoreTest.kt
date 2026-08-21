package org.monogram.data.mtproto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.monogram.data.db.dao.MtProtoStoryProjectionDao
import org.monogram.data.db.model.MtProtoStoryActiveListEntity
import org.monogram.data.db.model.MtProtoStoryListCursorEntity
import org.monogram.data.db.model.MtProtoStoryProjectionEntity

class MtProtoStoryProjectionStoreTest {
    private val production = MtProtoAuthKeyScope("slot_a", MtProtoEnvironment.PRODUCTION, 2)

    @Test
    fun `persists canonical story payload within its full scope`() = kotlinx.coroutines.runBlocking {
        val store = MtProtoRoomStoryProjectionStore(FakeDao(), nowMillis = { 1234L })
        val story = MtProtoStoryPayload(MtProtoStoryKey("USER", 9L, 7), byteArrayOf(1, 2), false)

        store.upsert(production, story)

        val restored = store.get(production, story.key)
        checkNotNull(restored)
        assertEquals(story.key, restored.key)
        assertArrayEquals(byteArrayOf(1, 2), restored.payload)
        assertEquals(false, restored.isDeleted)
        assertNull(store.get(MtProtoAuthKeyScope("slot_a", MtProtoEnvironment.TEST, 2), story.key))
        assertNull(store.get(MtProtoAuthKeyScope("slot_a", MtProtoEnvironment.PRODUCTION, 4), story.key))
    }

    @Test
    fun `replaces active list and persists its server cursor`() = kotlinx.coroutines.runBlocking {
        val store = MtProtoRoomStoryProjectionStore(FakeDao(), nowMillis = { 1234L })
        val first = MtProtoStoryActiveListItem(MtProtoStoryKey("USER", 9L, 7), 100L, true, 3)
        val second = MtProtoStoryActiveListItem(MtProtoStoryKey("CHANNEL", 4L, 2), 90L, false, 1)
        val cursor = MtProtoStoryListCursor("server-state", hasMore = true, totalCount = 8)

        store.replaceActiveList(production, "MAIN", listOf(second, first), cursor)
        store.replaceActiveList(production, "MAIN", listOf(second), cursor.copy(state = "next", hasMore = false))

        assertEquals(listOf(second), store.activeList(production, "MAIN"))
        assertEquals(MtProtoStoryListCursor("next", false, 8), store.cursor(production, "MAIN"))
    }

    @Test
    fun `deletes all story state for the account environment`() = kotlinx.coroutines.runBlocking {
        val dao = FakeDao()
        val store = MtProtoRoomStoryProjectionStore(dao)
        store.upsert(production, MtProtoStoryPayload(MtProtoStoryKey("USER", 9L, 7), byteArrayOf(1), false))
        store.replaceActiveList(production, "MAIN", emptyList(), MtProtoStoryListCursor("state", false, 0))

        store.deleteAccount("slot_a", MtProtoEnvironment.PRODUCTION)

        assertEquals(1, dao.deletedStories)
        assertEquals(1, dao.deletedLists)
        assertEquals(1, dao.deletedCursors)
    }

    private class FakeDao : MtProtoStoryProjectionDao {
        private val stories = mutableMapOf<List<Any>, MtProtoStoryProjectionEntity>()
        private val lists = mutableMapOf<List<Any>, MutableList<MtProtoStoryActiveListEntity>>()
        private val cursors = mutableMapOf<List<Any>, MtProtoStoryListCursorEntity>()
        var deletedStories = 0
        var deletedLists = 0
        var deletedCursors = 0

        override suspend fun upsertStory(entity: MtProtoStoryProjectionEntity) {
            stories[storyKey(entity.accountSlot, entity.environment, entity.dcId, entity.peerType, entity.peerId, entity.storyId)] = entity
        }

        override suspend fun getStory(accountSlot: String, environment: String, dcId: Int, peerType: String, peerId: Long, storyId: Int) =
            stories[storyKey(accountSlot, environment, dcId, peerType, peerId, storyId)]

        override suspend fun upsertActiveList(entries: List<MtProtoStoryActiveListEntity>) {
            entries.forEach { entry ->
                val key = listKey(entry.accountSlot, entry.environment, entry.dcId, entry.listType)
                lists.getOrPut(key) { mutableListOf() }.removeAll {
                    it.peerType == entry.peerType && it.peerId == entry.peerId && it.storyId == entry.storyId
                }
                lists.getValue(key) += entry
            }
        }

        override suspend fun clearActiveList(accountSlot: String, environment: String, dcId: Int, listType: String) {
            lists.remove(listKey(accountSlot, environment, dcId, listType))
        }

        override suspend fun getActiveList(accountSlot: String, environment: String, dcId: Int, listType: String) =
            lists[listKey(accountSlot, environment, dcId, listType)].orEmpty()
                .sortedWith(compareByDescending<MtProtoStoryActiveListEntity> { it.orderKey }.thenBy { it.peerType }.thenBy { it.peerId }.thenBy { it.storyId })

        override suspend fun updateMaxReadStoryId(accountSlot: String, environment: String, dcId: Int, peerType: String, peerId: Long, maxReadStoryId: Int, updatedAt: Long) {
            lists.values.flatten().filter {
                it.accountSlot == accountSlot && it.environment == environment && it.dcId == dcId &&
                    it.peerType == peerType && it.peerId == peerId
            }.forEach { entry ->
                lists.getValue(listKey(entry.accountSlot, entry.environment, entry.dcId, entry.listType)).remove(entry)
                lists.getValue(listKey(entry.accountSlot, entry.environment, entry.dcId, entry.listType)) += entry.copy(maxReadStoryId = maxReadStoryId, updatedAt = updatedAt)
            }
        }

        override suspend fun upsertCursor(entity: MtProtoStoryListCursorEntity) {
            cursors[listKey(entity.accountSlot, entity.environment, entity.dcId, entity.listType)] = entity
        }

        override suspend fun getCursor(accountSlot: String, environment: String, dcId: Int, listType: String) =
            cursors[listKey(accountSlot, environment, dcId, listType)]

        override suspend fun deleteStoriesForAccount(accountSlot: String, environment: String) {
            deletedStories++
            stories.entries.removeAll { (key, _) -> key[0] == accountSlot && key[1] == environment }
        }

        override suspend fun deleteActiveListsForAccount(accountSlot: String, environment: String) {
            deletedLists++
            lists.keys.removeAll { it[0] == accountSlot && it[1] == environment }
        }

        override suspend fun deleteCursorsForAccount(accountSlot: String, environment: String) {
            deletedCursors++
            cursors.keys.removeAll { it[0] == accountSlot && it[1] == environment }
        }

        private fun storyKey(accountSlot: String, environment: String, dcId: Int, peerType: String, peerId: Long, storyId: Int) =
            listOf(accountSlot, environment, dcId, peerType, peerId, storyId)

        private fun listKey(accountSlot: String, environment: String, dcId: Int, listType: String) =
            listOf(accountSlot, environment, dcId, listType)
    }
}
