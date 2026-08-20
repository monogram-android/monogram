package org.monogram.data.mtproto

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.monogram.data.db.dao.MtProtoFileHandleDao
import org.monogram.data.db.model.MtProtoFileHandleEntity

class MtProtoFileHandleStoreTest {
    private val primaryScope = MtProtoAuthKeyScope("account-a", MtProtoEnvironment.PRODUCTION, 2)

    @Test
    fun `reuses a stable opaque handle for the same scoped document`() = runTest {
        val store = MtProtoRoomFileHandleStore(FakeFileHandleDao())

        val first = store.getOrCreate(primaryScope, documentId = 99L)
        val second = store.getOrCreate(primaryScope, documentId = 99L)

        assertEquals(first, second)
        assertEquals(99L, first.documentId)
    }

    @Test
    fun `does not resolve a handle from another account or session dc`() = runTest {
        val store = MtProtoRoomFileHandleStore(FakeFileHandleDao())
        val handle = store.getOrCreate(primaryScope, documentId = 99L)

        assertNull(store.get(MtProtoAuthKeyScope("account-b", MtProtoEnvironment.PRODUCTION, 2), handle.fileId))
        assertNull(store.get(MtProtoAuthKeyScope("account-a", MtProtoEnvironment.PRODUCTION, 4), handle.fileId))
        assertEquals(handle, store.get(primaryScope, handle.fileId))
    }

    @Test
    fun `removes handles during account cleanup`() = runTest {
        val store = MtProtoRoomFileHandleStore(FakeFileHandleDao())
        val handle = store.getOrCreate(primaryScope, documentId = 99L)

        store.deleteAccount("account-a", MtProtoEnvironment.PRODUCTION)

        assertNull(store.get(primaryScope, handle.fileId))
    }

    private class FakeFileHandleDao : MtProtoFileHandleDao {
        private val entities = mutableListOf<MtProtoFileHandleEntity>()
        private var nextId = 1

        override suspend fun getByDocument(
            accountSlot: String,
            environment: String,
            sessionDcId: Int,
            documentId: Long,
        ): MtProtoFileHandleEntity? = entities.firstOrNull {
            it.accountSlot == accountSlot &&
                it.environment == environment &&
                it.sessionDcId == sessionDcId &&
                it.documentId == documentId
        }

        override suspend fun get(
            fileId: Int,
            accountSlot: String,
            environment: String,
            sessionDcId: Int,
        ): MtProtoFileHandleEntity? = entities.firstOrNull {
            it.fileId == fileId &&
                it.accountSlot == accountSlot &&
                it.environment == environment &&
                it.sessionDcId == sessionDcId
        }

        override suspend fun insert(entity: MtProtoFileHandleEntity): Long {
            if (getByDocument(entity.accountSlot, entity.environment, entity.sessionDcId, entity.documentId) != null) {
                return -1L
            }
            val stored = entity.copy(fileId = nextId++)
            entities += stored
            return stored.fileId.toLong()
        }

        override suspend fun deleteAccount(accountSlot: String, environment: String) {
            entities.removeAll { it.accountSlot == accountSlot && it.environment == environment }
        }
    }
}
