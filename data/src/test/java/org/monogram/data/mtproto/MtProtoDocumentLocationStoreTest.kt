package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.monogram.data.db.dao.MtProtoDocumentLocationDao
import org.monogram.data.db.model.MtProtoDocumentLocationEntity
import org.monogram.mtproto.tl.generated.cloud.layer223.Document_be725c3b31
import org.monogram.mtproto.tl.runtime.TlBytes

class MtProtoDocumentLocationStoreTest {
    @Test
    fun `persists document identity by account environment and session DC`() = runBlocking {
        val dao = FakeDao()
        val store = MtProtoRoomDocumentLocationStore(dao) { 123L }
        val scope = MtProtoAuthKeyScope("account", MtProtoEnvironment.TEST, 2)

        store.upsert(
            scope,
            Document_be725c3b31(
                id = 9L,
                accessHash = 10L,
                fileReference = TlBytes.copyOf(byteArrayOf(1, 2)),
                date = 0,
                mimeType = "application/pdf",
                size = 42L,
                thumbs = null,
                videoThumbs = null,
                dcId = 4,
                attributes = emptyList(),
            ),
        )

        val location = requireNotNull(store.get(scope, 9L))
        assertEquals(9L, location.documentId)
        assertEquals(10L, location.accessHash)
        assertEquals(listOf<Byte>(1, 2), location.fileReference.toList())
        assertEquals(4, location.documentDcId)
        assertEquals("application/pdf", location.mimeType)
        assertEquals(42L, location.size)
        assertEquals(MtProtoDocumentMediaKind.DOCUMENT, location.mediaKind)
        assertNull(store.get(scope.copy(dcId = 3), 9L))
        store.deleteAccount("account", MtProtoEnvironment.TEST)
        assertNull(store.get(scope, 9L))
    }

    private class FakeDao : MtProtoDocumentLocationDao {
        private val locations = mutableMapOf<List<Any>, MtProtoDocumentLocationEntity>()

        override suspend fun get(accountSlot: String, environment: String, sessionDcId: Int, documentId: Long) =
            locations[listOf(accountSlot, environment, sessionDcId, documentId)]

        override suspend fun upsert(entity: MtProtoDocumentLocationEntity) {
            locations[listOf(entity.accountSlot, entity.environment, entity.sessionDcId, entity.documentId)] = entity
        }

        override suspend fun deleteAccount(accountSlot: String, environment: String) {
            locations.entries.removeAll { (key, _) -> key[0] == accountSlot && key[1] == environment }
        }
    }
}
