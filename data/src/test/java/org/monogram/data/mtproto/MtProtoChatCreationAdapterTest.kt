package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.StorageCleanupResultModel

class MtProtoChatCreationAdapterTest {
    private class RecordingCreationRepository : MtProtoChatCreationRepository {
        var createGroupCalled = false
        var lastTitle: String? = null
        var lastUserIds: List<Long>? = null
        var createChannelCalled = false
        var lastChannelTitle: String? = null
        var lastDescription: String? = null
        var lastIsMegagroup = false

        override suspend fun createGroup(title: String, userIds: List<Long>, messageAutoDeleteTime: Int): Long {
            createGroupCalled = true; lastTitle = title; lastUserIds = userIds
            return 1L
        }

        override suspend fun createChannel(title: String, description: String, isMegagroup: Boolean, messageAutoDeleteTime: Int): Long {
            createChannelCalled = true; lastChannelTitle = title; lastDescription = description; lastIsMegagroup = isMegagroup
            return 2L
        }
    }

    private class RecordingDatabaseSizeReader : MtProtoDatabaseSizeReader {
        override fun sizeBytes(): Long = 42L
    }

    @Test
    fun `createGroup delegates to MTProto repository`() = runBlocking {
        val repo = RecordingCreationRepository()
        val adapter = MtProtoChatCreationAdapter(
            mtProtoFactory = { repo },
            mtProtoDatabaseSizeReaderFactory = { RecordingDatabaseSizeReader() },
            storageCleanupFactory = { error("not used") },
        )

        val result = adapter.createGroup("Test Group", listOf(1L, 2L), messageAutoDeleteTime = 3600)

        assertTrue(repo.createGroupCalled)
        assertEquals("Test Group", repo.lastTitle)
        assertEquals(listOf(1L, 2L), repo.lastUserIds)
        assertEquals(1L, result)
    }

    @Test
    fun `createChannel delegates to MTProto repository`() = runBlocking {
        val repo = RecordingCreationRepository()
        val adapter = MtProtoChatCreationAdapter(
            mtProtoFactory = { repo },
            mtProtoDatabaseSizeReaderFactory = { RecordingDatabaseSizeReader() },
            storageCleanupFactory = { error("not used") },
        )

        val result = adapter.createChannel("Test Channel", "desc", isMegagroup = true, messageAutoDeleteTime = 0)

        assertTrue(repo.createChannelCalled)
        assertEquals("Test Channel", repo.lastChannelTitle)
        assertEquals(true, repo.lastIsMegagroup)
        assertEquals(2L, result)
    }
    @Test
    fun `clearDatabase routes to storage cleanup`() = runBlocking {
        var cleared = false
        val adapter = MtProtoChatCreationAdapter(
            mtProtoFactory = { error("not used") },
            mtProtoDatabaseSizeReaderFactory = { RecordingDatabaseSizeReader() },
            storageCleanupFactory = { MtProtoStorageCleanupRepository { _ ->
                cleared = true
                StorageCleanupResultModel(freedSize = 0L, freedFileCount = 0, cleanupSucceeded = true)
            } },
        )

        adapter.clearDatabase()

        assertTrue(cleared)
    }

    @Test
    fun `getDatabaseSize delegates to database size reader`() {
        val adapter = MtProtoChatCreationAdapter(
            mtProtoFactory = { error("not used") },
            mtProtoDatabaseSizeReaderFactory = { RecordingDatabaseSizeReader() },
            storageCleanupFactory = { error("not used") },
        )

        assertEquals(42L, adapter.getDatabaseSize())
    }

}
