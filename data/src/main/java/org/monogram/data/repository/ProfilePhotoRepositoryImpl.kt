package org.monogram.data.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import org.drinkless.tdlib.TdApi
import org.monogram.data.core.coRunCatching
import org.monogram.data.datasource.cache.ChatLocalDataSource
import org.monogram.data.datasource.remote.UserRemoteDataSource
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.infra.FileDownloadQueue
import org.monogram.data.infra.FileObserverHub
import org.monogram.data.mapper.isValidFilePath
import org.monogram.data.mapper.toEntity
import org.monogram.data.mapper.user.toTdApiChat
import org.monogram.domain.repository.ProfilePhotoRepository
import org.monogram.domain.models.ProfilePhotoMedia

class ProfilePhotoRepositoryImpl(
    private val remote: UserRemoteDataSource,
    private val chatLocal: ChatLocalDataSource,
    private val gateway: TelegramGateway,
    private val fileQueue: FileDownloadQueue,
    private val fileObserverHub: FileObserverHub
) : ProfilePhotoRepository {
    private val avatarDownloadPriority = AVATAR_DOWNLOAD_PRIORITY

    override suspend fun getUserProfilePhotos(
        userId: Long,
        offset: Int,
        limit: Int
    ): List<ProfilePhotoMedia> {
        if (userId <= 0) return emptyList()
        val result = remote.getUserProfilePhotos(userId, offset, limit) ?: return emptyList()
        return coroutineScope {
            result.photos
                .map { photo -> async { resolveUserProfilePhotoMedia(photo) } }
                .awaitAll()
                .filterNotNull()
        }
    }

    override suspend fun getChatProfilePhotos(
        chatId: Long,
        offset: Int,
        limit: Int
    ): List<ProfilePhotoMedia> {
        if (chatId == 0L) return emptyList()
        val paths = loadChatPhotoHistory(chatId, offset, limit)
        if (paths.isNotEmpty()) return paths

        val currentPath = resolveCurrentChatPhoto(chatId)
        return listOfNotNull(currentPath)
    }

    override fun getUserProfilePhotosFlow(userId: Long): Flow<List<ProfilePhotoMedia>> =
        channelFlow {
        if (userId <= 0) {
            send(emptyList())
            return@channelFlow
        }

        var trackedFileIds = emptySet<Int>()

        suspend fun reload() {
            val loaded = getUserProfilePhotosWithTracking(userId)
            trackedFileIds = loaded.second
            send(loaded.first)
        }

        reload()

        fileObserverHub.fileStates.collectLatest { state ->
            if (state.fileId in trackedFileIds) {
                reload()
            }
        }
    }

    override fun getChatProfilePhotosFlow(chatId: Long): Flow<List<ProfilePhotoMedia>> =
        channelFlow {
        if (chatId == 0L) {
            send(emptyList())
            return@channelFlow
        }

        var trackedFileIds = emptySet<Int>()

        suspend fun reload() {
            val loaded = getChatProfilePhotosWithTracking(chatId)
            trackedFileIds = loaded.second
            send(loaded.first)
        }

        reload()

        fileObserverHub.fileStates.collectLatest { state ->
            if (state.fileId in trackedFileIds) {
                reload()
            }
        }
    }

    private suspend fun getUserProfilePhotosWithTracking(
        userId: Long,
        offset: Int = 0,
        limit: Int = 10
    ): Pair<List<ProfilePhotoMedia>, Set<Int>> {
        if (userId <= 0) return emptyList<ProfilePhotoMedia>() to emptySet()
        val trackedFileIds = linkedSetOf<Int>()
        val result = remote.getUserProfilePhotos(userId, offset, limit)
            ?: return emptyList<ProfilePhotoMedia>() to emptySet()
        val paths = coroutineScope {
            result.photos
                .map { photo ->
                    async {
                        resolveUserProfilePhotoMedia(
                            photo,
                            trackedFileIds
                        )
                    }
                }
                .awaitAll()
                .filterNotNull()
        }
        return paths to trackedFileIds
    }

    private suspend fun getChatProfilePhotosWithTracking(
        chatId: Long,
        offset: Int = 0,
        limit: Int = 10
    ): Pair<List<ProfilePhotoMedia>, Set<Int>> {
        if (chatId == 0L) return emptyList<ProfilePhotoMedia>() to emptySet()
        val trackedFileIds = linkedSetOf<Int>()
        val paths = loadChatPhotoHistory(chatId, offset, limit, trackedFileIds)
        if (paths.isNotEmpty()) return paths to trackedFileIds

        val currentPath = resolveCurrentChatPhoto(chatId, trackedFileIds)
        return listOfNotNull(currentPath) to trackedFileIds
    }

    private suspend fun loadChatPhotoHistory(
        chatId: Long,
        offset: Int,
        limit: Int,
        trackedFileIds: MutableSet<Int>? = null
    ): List<ProfilePhotoMedia> {
        if (limit <= 0) return emptyList()

        val request = TdApi.SearchChatMessages().apply {
            this.chatId = chatId
            this.query = ""
            this.senderId = null
            this.fromMessageId = 0L
            this.offset = 0
            this.limit = (offset + limit).coerceAtMost(100)
            this.filter = TdApi.SearchMessagesFilterChatPhoto()
        }

        val result = coRunCatching {
            gateway.execute(request) as? TdApi.FoundChatMessages
        }.getOrNull() ?: return emptyList()

        val chatPhotos = result.messages
            .asSequence()
            .mapNotNull { (it.content as? TdApi.MessageChatChangePhoto)?.photo }
            .drop(offset)
            .take(limit)
            .toList()

        if (chatPhotos.isEmpty()) return emptyList()

        return coroutineScope {
            chatPhotos
                .map { photo ->
                    async {
                        resolveUserProfilePhotoMedia(
                            photo,
                            trackedFileIds
                        )
                    }
                }
                .awaitAll()
                .filterNotNull()
                .distinct()
        }
    }

    private suspend fun resolveCurrentChatPhoto(
        chatId: Long,
        trackedFileIds: MutableSet<Int>? = null
    ): ProfilePhotoMedia? {
        val chat = remote.getChat(chatId)?.also { chatLocal.insertChat(it.toEntity()) }
            ?: chatLocal.getChat(chatId)?.toTdApiChat()
            ?: return null
        return resolveChatPhotoInfo(chat.photo, trackedFileIds)
    }

    private suspend fun resolveChatPhotoInfo(
        photoInfo: TdApi.ChatPhotoInfo?,
        trackedFileIds: MutableSet<Int>? = null
    ): ProfilePhotoMedia? {
        val smallId = photoInfo?.small?.id?.takeIf { it != 0 }
        val bigId = photoInfo?.big?.id?.takeIf { it != 0 }
        smallId?.let { trackedFileIds?.add(it) }
        bigId?.let { trackedFileIds?.add(it) }
        val previewFile = photoInfo?.small ?: photoInfo?.big ?: return null
        val previewPath = previewFile.local.path.takeIf { isValidFilePath(it) }
            ?: resolveDownloadedFilePath(previewFile.id)
        if (previewPath == null && previewFile.id != 0) {
            fileQueue.enqueue(
                fileId = previewFile.id,
                priority = avatarDownloadPriority,
                type = FileDownloadQueue.DownloadType.DEFAULT,
                synchronous = false
            )
        }
        val originalFile = photoInfo?.big ?: previewFile
        return ProfilePhotoMedia(
            id = originalFile.id.toLong(),
            previewPath = previewPath,
            originalFileId = originalFile.id,
            originalPath = originalFile.local.path.takeIf {
                originalFile.local.isDownloadingCompleted && isValidFilePath(
                    it
                )
            }
                ?: resolveDownloadedFilePath(originalFile.id)
        )
    }

    private suspend fun resolveUserProfilePhotoMedia(
        photo: TdApi.ChatPhoto,
        trackedFileIds: MutableSet<Int>? = null
    ): ProfilePhotoMedia? {
        val animationFile = photo.animation?.file
        val animationFileId = animationFile?.id?.takeIf { it != 0 }
        animationFileId?.let { trackedFileIds?.add(it) }
        val animationPath = animationFile?.local?.path?.takeIf { isValidFilePath(it) }
        val downloadedAnimationPath = resolveDownloadedFilePath(animationFileId)
        if (animationFileId != null && animationPath == null && downloadedAnimationPath == null) {
            fileQueue.enqueue(
                fileId = animationFileId,
                priority = avatarDownloadPriority,
                type = FileDownloadQueue.DownloadType.DEFAULT,
                synchronous = false
            )
        }

        photo.sizes.forEach { size ->
            size.photo.id.takeIf { it != 0 }?.let { trackedFileIds?.add(it) }
        }

        val bestPhotoFile = photo.sizes
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?.photo
            ?: photo.sizes.lastOrNull()?.photo
            ?: return null

        val fallbackFile = photo.sizes.find { it.type == "m" }?.photo
            ?: photo.sizes.find { it.type == "s" }?.photo
            ?: photo.sizes.firstOrNull()?.photo
            ?: bestPhotoFile
        val previewPath = fallbackFile.local.path.takeIf { isValidFilePath(it) }
            ?: resolveDownloadedFilePath(fallbackFile.id)
        if (previewPath == null && fallbackFile.id != 0) {
            fileQueue.enqueue(
                fileId = fallbackFile.id,
                priority = avatarDownloadPriority,
                type = FileDownloadQueue.DownloadType.DEFAULT,
                synchronous = false
            )
        }
        return ProfilePhotoMedia(
            id = photo.id,
            previewPath = previewPath,
            originalFileId = bestPhotoFile.id,
            originalPath = bestPhotoFile.local.path.takeIf {
                bestPhotoFile.local.isDownloadingCompleted && isValidFilePath(
                    it
                )
            }
                ?: resolveDownloadedFilePath(bestPhotoFile.id),
            animationFileId = animationFileId ?: 0,
            animationPath = animationPath ?: downloadedAnimationPath
        )
    }

    private suspend fun resolveDownloadedFilePath(fileId: Int?): String? {
        if (fileId == null || fileId == 0) return null
        val file = fileObserverHub.getCachedFile(fileId)
            ?: coRunCatching { gateway.execute(TdApi.GetFile(fileId)) }.getOrNull()
            ?: return null
        return if (file.local.isDownloadingCompleted) {
            file.local.path.takeIf { isValidFilePath(it) }
        } else {
            null
        }
    }

    companion object {
        private const val AVATAR_DOWNLOAD_PRIORITY = 24
    }
}
