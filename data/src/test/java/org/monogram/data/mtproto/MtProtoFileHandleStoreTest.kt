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
        val resource = MtProtoFileResourceKey(MtProtoFileResourceType.DOCUMENT, id = 99L)

        val first = store.getOrCreate(primaryScope, resource)
        val second = store.getOrCreate(primaryScope, resource)

        assertEquals(first, second)
        assertEquals(resource, first.resource)
    }

    @Test
    fun `creates distinct handles for photo sizes of the same photo`() = runTest {
        val store = MtProtoRoomFileHandleStore(FakeFileHandleDao())

        val medium = store.getOrCreate(primaryScope, MtProtoFileResourceKey(MtProtoFileResourceType.PHOTO, 99L, "m"))
        val large = store.getOrCreate(primaryScope, MtProtoFileResourceKey(MtProtoFileResourceType.PHOTO, 99L, "x"))

        assertEquals(MtProtoFileResourceType.PHOTO, medium.resource.type)
        assertEquals("m", medium.resource.variant)
        assertEquals("x", large.resource.variant)
        assertEquals(false, medium.fileId == large.fileId)
    }

    @Test
    fun `does not resolve a handle from another account or session dc`() = runTest {
        val store = MtProtoRoomFileHandleStore(FakeFileHandleDao())
        val handle = store.getOrCreate(primaryScope, MtProtoFileResourceKey(MtProtoFileResourceType.DOCUMENT, 99L))

        assertNull(store.get(MtProtoAuthKeyScope("account-b", MtProtoEnvironment.PRODUCTION, 2), handle.fileId))
        assertNull(store.get(MtProtoAuthKeyScope("account-a", MtProtoEnvironment.PRODUCTION, 4), handle.fileId))
        assertEquals(handle, store.get(primaryScope, handle.fileId))
    }

    @Test
    fun `removes handles during account cleanup`() = runTest {
        val store = MtProtoRoomFileHandleStore(FakeFileHandleDao())
        val handle = store.getOrCreate(primaryScope, MtProtoFileResourceKey(MtProtoFileResourceType.DOCUMENT, 99L))

        store.deleteAccount("account-a", MtProtoEnvironment.PRODUCTION)

        assertNull(store.get(primaryScope, handle.fileId))
    }

    private class FakeFileHandleDao : MtProtoFileHandleDao {
        private val entities = mutableListOf<MtProtoFileHandleEntity>()
        private var nextId = 1

        override suspend fun getByResource(
            accountSlot: String,
            environment: String,
            sessionDcId: Int,
            resourceType: String,
            resourceId: Long,
            resourceVariant: String,
        ): MtProtoFileHandleEntity? = entities.firstOrNull {
            it.accountSlot == accountSlot &&
                it.environment == environment &&
                it.sessionDcId == sessionDcId &&
                it.resourceType == resourceType &&
                it.resourceId == resourceId &&
                it.resourceVariant == resourceVariant
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
            if (getByResource(
                    entity.accountSlot,
                    entity.environment,
                    entity.sessionDcId,
                    entity.resourceType,
                    entity.resourceId,
                    entity.resourceVariant,
                ) != null
            ) return -1L
            val stored = entity.copy(fileId = nextId++)
            entities += stored
            return stored.fileId.toLong()
        }

        override suspend fun deleteAccount(accountSlot: String, environment: String) {
            entities.removeAll { it.accountSlot == accountSlot && it.environment == environment }
        }
    }
}
