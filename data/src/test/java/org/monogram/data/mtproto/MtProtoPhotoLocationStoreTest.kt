package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.monogram.data.db.dao.MtProtoPhotoLocationDao
import org.monogram.data.db.model.MtProtoPhotoLocationEntity
import org.monogram.mtproto.tl.generated.cloud.layer223.PhotoSizeProgressive
import org.monogram.mtproto.tl.generated.cloud.layer223.PhotoSize_65b79bf448
import org.monogram.mtproto.tl.generated.cloud.layer223.Photo_97e0ed8316
import org.monogram.mtproto.tl.runtime.TlBytes

class MtProtoPhotoLocationStoreTest {
    @Test
    fun `persists concrete photo variants by account environment and session DC`() = runBlocking {
        val dao = FakeDao()
        val store = MtProtoRoomPhotoLocationStore(dao) { 123L }
        val scope = MtProtoAuthKeyScope("account", MtProtoEnvironment.TEST, 2)

        store.upsert(
            scope,
            Photo_97e0ed8316(
                hasStickers = false,
                id = 9L,
                accessHash = 10L,
                fileReference = TlBytes.copyOf(byteArrayOf(1, 2)),
                date = 0,
                sizes = listOf(
                    PhotoSize_65b79bf448("m", 320, 240, 42),
                    PhotoSizeProgressive("x", 640, 480, listOf(20, 80)),
                ),
                videoSizes = null,
                dcId = 4,
            ),
        )

        val medium = requireNotNull(store.get(scope, 9L, "m"))
        val progressive = requireNotNull(store.get(scope, 9L, "x"))
        assertEquals(320, medium.width)
        assertEquals(42L, medium.size)
        assertEquals(640, progressive.width)
        assertEquals(80L, progressive.size)
        assertNull(store.get(scope.copy(dcId = 3), 9L, "m"))
        store.deleteAccount("account", MtProtoEnvironment.TEST)
        assertNull(store.get(scope, 9L, "m"))
    }

    private class FakeDao : MtProtoPhotoLocationDao {
        private val locations = mutableMapOf<List<Any>, MtProtoPhotoLocationEntity>()

        override suspend fun get(
            accountSlot: String,
            environment: String,
            sessionDcId: Int,
            photoId: Long,
            thumbSize: String,
        ) = locations[listOf(accountSlot, environment, sessionDcId, photoId, thumbSize)]

        override suspend fun getLargest(
            accountSlot: String,
            environment: String,
            sessionDcId: Int,
            photoId: Long,
        ) = locations.values
            .filter { it.accountSlot == accountSlot && it.environment == environment && it.sessionDcId == sessionDcId && it.photoId == photoId }
            .maxWithOrNull(compareBy<MtProtoPhotoLocationEntity> { it.width.toLong() * it.height }.thenBy { it.size })

        override suspend fun upsert(entities: List<MtProtoPhotoLocationEntity>) {
            entities.forEach { entity ->
                locations[listOf(entity.accountSlot, entity.environment, entity.sessionDcId, entity.photoId, entity.thumbSize)] = entity
            }
        }

        override suspend fun deleteAccount(accountSlot: String, environment: String) {
            locations.entries.removeAll { (key, _) -> key[0] == accountSlot && key[1] == environment }
        }
    }
}
