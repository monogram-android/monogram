package org.monogram.data.mtproto

import org.monogram.data.db.dao.MtProtoPhotoLocationDao
import org.monogram.data.db.model.MtProtoPhotoLocationEntity
import org.monogram.mtproto.tl.generated.cloud.layer223.PhotoSizeProgressive
import org.monogram.mtproto.tl.generated.cloud.layer223.PhotoSize_65b79bf448
import org.monogram.mtproto.tl.generated.cloud.layer223.Photo_97e0ed8316

internal data class MtProtoPhotoLocation(
    val photoId: Long,
    val thumbSize: String,
    val accessHash: Long,
    val fileReference: ByteArray,
    val photoDcId: Int,
    val width: Int,
    val height: Int,
    val size: Long,
)

internal interface MtProtoPhotoLocationStore {
    suspend fun upsert(scope: MtProtoAuthKeyScope, photo: Photo_97e0ed8316)
    suspend fun get(scope: MtProtoAuthKeyScope, photoId: Long, thumbSize: String): MtProtoPhotoLocation?
    suspend fun getLargest(scope: MtProtoAuthKeyScope, photoId: Long): MtProtoPhotoLocation?
    suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment)
}

internal object NoOpMtProtoPhotoLocationStore : MtProtoPhotoLocationStore {
    override suspend fun upsert(scope: MtProtoAuthKeyScope, photo: Photo_97e0ed8316) = Unit
    override suspend fun get(scope: MtProtoAuthKeyScope, photoId: Long, thumbSize: String): MtProtoPhotoLocation? = null
    override suspend fun getLargest(scope: MtProtoAuthKeyScope, photoId: Long): MtProtoPhotoLocation? = null
    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) = Unit
}

internal class MtProtoRoomPhotoLocationStore(
    private val dao: MtProtoPhotoLocationDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : MtProtoPhotoLocationStore {
    override suspend fun upsert(scope: MtProtoAuthKeyScope, photo: Photo_97e0ed8316) {
        val locations = photo.sizes.mapNotNull { size ->
            when (size) {
                is PhotoSize_65b79bf448 -> PhotoSizeLocation(size.type, size.w, size.h, size.size.toLong())
                is PhotoSizeProgressive -> PhotoSizeLocation(size.type, size.w, size.h, size.sizes.lastOrNull()?.toLong() ?: return@mapNotNull null)
                else -> null
            }
        }
        if (locations.isEmpty()) return
        dao.upsert(
            locations.map { size ->
                MtProtoPhotoLocationEntity(
                    accountSlot = scope.accountSlot,
                    environment = scope.environment.storageName,
                    sessionDcId = scope.dcId,
                    photoId = photo.id,
                    thumbSize = size.type,
                    accessHash = photo.accessHash,
                    fileReference = photo.fileReference.toByteArray(),
                    photoDcId = photo.dcId,
                    width = size.width,
                    height = size.height,
                    size = size.size,
                    updatedAt = nowMillis(),
                )
            }
        )
    }

    override suspend fun get(scope: MtProtoAuthKeyScope, photoId: Long, thumbSize: String): MtProtoPhotoLocation? =
        dao.get(scope.accountSlot, scope.environment.storageName, scope.dcId, photoId, thumbSize)?.toLocation()

    override suspend fun getLargest(scope: MtProtoAuthKeyScope, photoId: Long): MtProtoPhotoLocation? =
        dao.getLargest(scope.accountSlot, scope.environment.storageName, scope.dcId, photoId)?.toLocation()

    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) =
        dao.deleteAccount(accountSlot, environment.storageName)

    private fun MtProtoPhotoLocationEntity.toLocation() = MtProtoPhotoLocation(
        photoId = photoId,
        thumbSize = thumbSize,
        accessHash = accessHash,
        fileReference = fileReference,
        photoDcId = photoDcId,
        width = width,
        height = height,
        size = size,
    )

    private data class PhotoSizeLocation(
        val type: String,
        val width: Int,
        val height: Int,
        val size: Long,
    )
}
