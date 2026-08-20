package org.monogram.data.mtproto

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.monogram.domain.models.ProfilePhotoMedia
import org.monogram.domain.repository.ProfilePhotoRepository
import org.monogram.mtproto.tl.generated.cloud.layer223.InputUserSelf
import org.monogram.mtproto.tl.generated.cloud.layer223.InputUser_0bd9c3151c
import org.monogram.mtproto.tl.generated.cloud.layer223.InputUser_4020eae812
import org.monogram.mtproto.tl.generated.cloud.layer223.Photo_97e0ed8316
import org.monogram.mtproto.tl.generated.cloud.layer223.photos.GetUserPhotos
import org.monogram.mtproto.tl.generated.cloud.layer223.photos.Photos_2ce0e3edca

/** Reads user profile photos through photos.getUserPhotos and exposes only opaque file handles. */
internal class MtProtoProfilePhotoRepository(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val users: MtProtoUserProjectionStore,
    private val locations: MtProtoPhotoLocationStore,
    private val files: MtProtoFileRepository,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : ProfilePhotoRepository {
    private val photosByUser = MutableStateFlow<Map<Long, List<ProfilePhotoMedia>>>(emptyMap())

    override suspend fun getUserProfilePhotos(userId: Long, offset: Int, limit: Int): List<ProfilePhotoMedia> {
        require(userId > 0L) { "MTProto user ID must be positive" }
        require(offset >= 0) { "MTProto profile photo offset must not be negative" }
        require(limit in 1..MAX_PHOTOS_PER_REQUEST) { "MTProto profile photo limit is invalid" }

        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val inputUser = inputUser(scope, userId) ?: return emptyList()
        val result = transportFactory.open(accountSlot).use { transport ->
            transport.execute(GetUserPhotos(inputUser, offset, 0L, limit))
        }
        val response = result as? Photos_2ce0e3edca ?: return emptyList()
        users.upsert(scope, response.users)
        val media = response.photos.mapNotNull { photo ->
            (photo as? Photo_97e0ed8316)?.let { toMedia(scope, userId, it) }
        }
        photosByUser.value = photosByUser.value + (userId to media)
        return media
    }

    override suspend fun getChatProfilePhotos(chatId: Long, offset: Int, limit: Int): List<ProfilePhotoMedia> =
        unsupported("chat profile photo history")

    override fun getUserProfilePhotosFlow(userId: Long): Flow<List<ProfilePhotoMedia>> =
        photosByUser.map { it[userId].orEmpty() }

    override fun getChatProfilePhotosFlow(chatId: Long): Flow<List<ProfilePhotoMedia>> =
        unsupported("chat profile photo history")

    private suspend fun inputUser(scope: MtProtoAuthKeyScope, userId: Long): InputUser_0bd9c3151c? {
        val user = users.get(scope, userId) ?: return null
        return if (user.isSelf) {
            InputUserSelf
        } else {
            user.accessHash?.let { InputUser_4020eae812(userId, it) }
        }
    }

    private suspend fun toMedia(
        scope: MtProtoAuthKeyScope,
        userId: Long,
        photo: Photo_97e0ed8316,
    ): ProfilePhotoMedia? {
        locations.upsert(scope, photo)
        val file = files.registerPhoto(photo.id, userId, photo.id) ?: return null
        return ProfilePhotoMedia(
            id = photo.id,
            previewPath = files.getPath(file.fileId),
            originalFileId = file.fileId,
            originalPath = files.getPath(file.fileId),
        )
    }

    private fun unsupported(operation: String): Nothing = throw UnsupportedOperationException(
        "MTProto $operation is not available"
    )

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
        const val MAX_PHOTOS_PER_REQUEST = 100
    }
}
