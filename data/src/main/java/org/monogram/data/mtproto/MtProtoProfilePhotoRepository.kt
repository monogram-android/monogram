package org.monogram.data.mtproto

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.monogram.domain.models.ProfilePhotoMedia
import org.monogram.domain.repository.ProfilePhotoRepository
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeer
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.InputMessagesFilterChatPhotos
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaPhoto
import org.monogram.mtproto.tl.generated.cloud.layer223.Message_7b7ecf54a3
import org.monogram.mtproto.tl.generated.cloud.layer223.InputUserSelf
import org.monogram.mtproto.tl.generated.cloud.layer223.InputUser_0bd9c3151c
import org.monogram.mtproto.tl.generated.cloud.layer223.InputUser_4020eae812
import org.monogram.mtproto.tl.generated.cloud.layer223.Photo_97e0ed8316
import org.monogram.mtproto.tl.generated.cloud.layer223.photos.GetUserPhotos
import org.monogram.mtproto.tl.generated.cloud.layer223.photos.Photos_2ce0e3edca
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.Search

/** Reads user profile photos through photos.getUserPhotos and exposes only opaque file handles. */
internal class MtProtoProfilePhotoRepository(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val users: MtProtoUserProjectionStore,
    private val chats: MtProtoChatProjectionStore,
    private val resultStager: MtProtoHistoryResultStager,
    private val locations: MtProtoPhotoLocationStore,
    private val files: MtProtoFileRepository,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : ProfilePhotoRepository {
    private val photosByUser = MutableStateFlow<Map<Long, List<ProfilePhotoMedia>>>(emptyMap())
    private val photosByChat = MutableStateFlow<Map<Long, List<ProfilePhotoMedia>>>(emptyMap())

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

    override suspend fun getChatProfilePhotos(chatId: Long, offset: Int, limit: Int): List<ProfilePhotoMedia> {
        require(offset >= 0) { "MTProto profile photo offset must not be negative" }
        require(limit in 1..MAX_PHOTOS_PER_REQUEST) { "MTProto profile photo limit is invalid" }
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val peer = resolveChatPeer(scope, chatId)
        val messages = transportFactory.open(accountSlot).use { transport ->
            resultStager.stageQuery(
                scope,
                transport.execute(
                    Search(
                        peer = peer,
                        q = "",
                        fromId = null,
                        savedPeerId = null,
                        savedReaction = null,
                        topMsgId = null,
                        filter = InputMessagesFilterChatPhotos,
                        minDate = 0,
                        maxDate = 0,
                        offsetId = 0,
                        addOffset = offset,
                        limit = limit,
                        maxId = 0,
                        minId = 0,
                        hash = 0L,
                    ),
                ),
            )
        }
        val media = messages.mapNotNull { message ->
            (message as? Message_7b7ecf54a3)?.let { it.media as? MessageMediaPhoto }
                ?.photo
                ?.let { it as? Photo_97e0ed8316 }
                ?.let { photo -> toMedia(scope, chatId, photo, message.id) }
        }
        photosByChat.value = photosByChat.value + (chatId to media)
        return media
    }

    override fun getUserProfilePhotosFlow(userId: Long): Flow<List<ProfilePhotoMedia>> =
        photosByUser.map { it[userId].orEmpty() }

    override fun getChatProfilePhotosFlow(chatId: Long): Flow<List<ProfilePhotoMedia>> =
        photosByChat.map { it[chatId].orEmpty() }

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

    private suspend fun toMedia(
        scope: MtProtoAuthKeyScope,
        chatId: Long,
        photo: Photo_97e0ed8316,
        messageId: Int,
    ): ProfilePhotoMedia? {
        locations.upsert(scope, photo)
        val file = files.registerPhoto(photo.id, chatId, messageId.toLong()) ?: return null
        return ProfilePhotoMedia(
            id = photo.id,
            previewPath = files.getPath(file.fileId),
            originalFileId = file.fileId,
            originalPath = files.getPath(file.fileId),
        )
    }

    private suspend fun resolveChatPeer(scope: MtProtoAuthKeyScope, chatId: Long): InputPeer {
        require(chatId < 0L) { "MTProto chat profile photos require a group or channel" }
        val rawId = if (chatId <= -CHANNEL_OFFSET - 1L) -(chatId + CHANNEL_OFFSET) else -chatId
        val chat = requireNotNull(chats.get(scope, rawId)) { "Missing MTProto chat projection: $rawId" }
        val peer = TelegramPeerChatId.decode(chatId, chat.type == MtProtoChatType.CHANNEL)
        return when (peer.type) {
            DialogPeerType.BASIC_GROUP -> InputPeerChat(peer.id)
            DialogPeerType.SUPERGROUP, DialogPeerType.CHANNEL -> InputPeerChannel(
                peer.id,
                requireNotNull(chat.accessHash) { "Missing MTProto channel access hash: ${peer.id}" },
            )
            DialogPeerType.PRIVATE, DialogPeerType.UNKNOWN -> error("MTProto chat profile photos require a group or channel")
        }
    }

    private fun unsupported(operation: String): Nothing = throw UnsupportedOperationException(
        "MTProto $operation is not available"
    )

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
        const val MAX_PHOTOS_PER_REQUEST = 100
        const val CHANNEL_OFFSET = 1_000_000_000_000L
    }
}
