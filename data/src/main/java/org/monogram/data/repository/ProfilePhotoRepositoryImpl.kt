package org.monogram.data.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import org.monogram.data.core.coRunCatching
import org.monogram.data.datasource.cache.ChatLocalDataSource
import org.monogram.data.datasource.remote.UserRemoteDataSource
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.data.infra.FileDownloadQueue
import org.monogram.data.mapper.toEntity
import org.monogram.data.mapper.user.toTdApiChat
import org.monogram.domain.repository.ProfilePhotoRepository

class ProfilePhotoRepositoryImpl(
    private val remote: UserRemoteDataSource,
    private val chatLocal: ChatLocalDataSource,
    private val gateway: TelegramGateway,
    private val updates: UpdateDispatcher,
    private val fileQueue: FileDownloadQueue
) : ProfilePhotoRepository {
    private val avatarDownloadPriority = AVATAR_DOWNLOAD_PRIORITY
    private val avatarHdPrefetchPriority = AVATAR_HD_PREFETCH_PRIORITY

    override suspend fun getUserProfilePhotos(
        userId: Long,
        offset: Int,
        limit: Int,
        ensureFullRes: Boolean
    ): List<String> {
        if (userId <= 0) return emptyList()
        val result = remote.getUserProfilePhotos(userId, offset, limit) ?: return emptyList()
        return coroutineScope {
            result.photos
                .map { photo -> async { resolveUserProfilePhotoPath(photo, ensureFullRes) } }
                .awaitAll()
                .filterNotNull()
        }
    }

    override suspend fun getChatProfilePhotos(
        chatId: Long,
        offset: Int,
        limit: Int,
        ensureFullRes: Boolean
    ): List<String> {
        if (chatId == 0L) return emptyList()
        val paths = loadChatPhotoHistoryPaths(chatId, offset, limit, ensureFullRes)
        if (paths.isNotEmpty()) return paths

        val currentPath = resolveCurrentChatPhotoPath(chatId, ensureFullRes)
        return listOfNotNull(currentPath)
    }

    override fun getUserProfilePhotosFlow(userId: Long): Flow<List<String>> = flow {
        if (userId <= 0) {
            emit(emptyList())
            return@flow
        }
        emit(getUserProfilePhotos(userId))
        updates.file.collect { emit(getUserProfilePhotos(userId)) }
    }

    override fun getChatProfilePhotosFlow(chatId: Long): Flow<List<String>> = flow {
        if (chatId == 0L) {
            emit(emptyList())
            return@flow
        }
        emit(getChatProfilePhotos(chatId))
        updates.file.collect { emit(getChatProfilePhotos(chatId)) }
    }

    private suspend fun loadChatPhotoHistoryPaths(
        chatId: Long,
        offset: Int,
        limit: Int,
        ensureFullRes: Boolean
    ): List<String> {
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
                .map { photo -> async { resolveUserProfilePhotoPath(photo, ensureFullRes) } }
                .awaitAll()
                .filterNotNull()
                .distinct()
        }
    }

    private suspend fun resolveCurrentChatPhotoPath(chatId: Long, ensureFullRes: Boolean): String? {
        val chat = remote.getChat(chatId)?.also { chatLocal.insertChat(it.toEntity()) }
            ?: chatLocal.getChat(chatId)?.toTdApiChat()
            ?: return null
        return resolveChatPhotoInfoPath(chat.photo, ensureFullRes)
    }

    private suspend fun resolveChatPhotoInfoPath(
        photoInfo: TdApi.ChatPhotoInfo?,
        ensureFullRes: Boolean
    ): String? {
        val smallId = photoInfo?.small?.id?.takeIf { it != 0 }
        val bigId = photoInfo?.big?.id?.takeIf { it != 0 }
        val preferredFile = if (ensureFullRes) {
            photoInfo?.big ?: photoInfo?.small
        } else {
            photoInfo?.small ?: photoInfo?.big
        } ?: return null

        val directPath = preferredFile.local.path.ifEmpty { null }
        if (directPath != null) {
            if (!ensureFullRes && bigId != null && bigId != preferredFile.id) {
                fileQueue.enqueue(
                    bigId,
                    avatarHdPrefetchPriority,
                    FileDownloadQueue.DownloadType.DEFAULT,
                    synchronous = false
                )
            }
            return directPath
        }

        val downloadedPath = resolveDownloadedFilePath(preferredFile.id)
        if (downloadedPath != null) {
            if (!ensureFullRes && bigId != null && bigId != preferredFile.id) {
                fileQueue.enqueue(
                    bigId,
                    avatarHdPrefetchPriority,
                    FileDownloadQueue.DownloadType.DEFAULT,
                    synchronous = false
                )
            }
            return downloadedPath
        }

        if (!ensureFullRes) {
            if (smallId != null) {
                fileQueue.enqueue(
                    smallId,
                    avatarDownloadPriority,
                    FileDownloadQueue.DownloadType.DEFAULT,
                    synchronous = false
                )
                if (bigId != null && bigId != smallId) {
                    fileQueue.enqueue(
                        bigId,
                        avatarHdPrefetchPriority,
                        FileDownloadQueue.DownloadType.DEFAULT,
                        synchronous = false
                    )
                }
            } else if (bigId != null) {
                fileQueue.enqueue(
                    bigId,
                    avatarDownloadPriority,
                    FileDownloadQueue.DownloadType.DEFAULT,
                    synchronous = false
                )
            }
            return null
        }

        val fileId = preferredFile.id.takeIf { it != 0 } ?: return null
        fileQueue.enqueue(
            fileId = fileId,
            priority = FULL_RES_DOWNLOAD_PRIORITY,
            type = FileDownloadQueue.DownloadType.DEFAULT,
            synchronous = false,
            ignoreSuppression = true
        )
        withTimeoutOrNull(FILE_DOWNLOAD_TIMEOUT_MS) {
            coRunCatching { fileQueue.waitForDownload(fileId).await() }
        }
        return resolveDownloadedFilePath(fileId)
    }

    private suspend fun resolveUserProfilePhotoPath(
        photo: TdApi.ChatPhoto,
        ensureFullRes: Boolean
    ): String? {
        val animationFile = photo.animation?.file
        val animationPath = animationFile?.local?.path?.ifEmpty { null }
        if (animationPath != null) return animationPath

        val bestPhotoFile = photo.sizes
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?.photo
            ?: photo.sizes.lastOrNull()?.photo
            ?: return null

        val directPath = bestPhotoFile.local.path.ifEmpty { null }
        if (directPath != null) return directPath

        if (!ensureFullRes) {
            val fallbackFile = photo.sizes.find { it.type == "m" }?.photo
                ?: photo.sizes.find { it.type == "s" }?.photo
                ?: photo.sizes.find { it.type == "c" }?.photo
                ?: photo.sizes.find { it.type == "b" }?.photo
                ?: photo.sizes.find { it.type == "a" }?.photo
                ?: photo.sizes.firstOrNull()?.photo

            val fallbackDirectPath = fallbackFile?.local?.path?.ifEmpty { null }
            if (fallbackDirectPath != null) return fallbackDirectPath

            val fallbackDownloadedPath = resolveDownloadedFilePath(fallbackFile?.id)
            if (fallbackDownloadedPath != null) return fallbackDownloadedPath

            return null
        }

        val fileId = bestPhotoFile.id.takeIf { it != 0 } ?: return null
        fileQueue.enqueue(
            fileId = fileId,
            priority = FULL_RES_DOWNLOAD_PRIORITY,
            type = FileDownloadQueue.DownloadType.DEFAULT,
            synchronous = false,
            ignoreSuppression = true
        )
        withTimeoutOrNull(FILE_DOWNLOAD_TIMEOUT_MS) {
            coRunCatching { fileQueue.waitForDownload(fileId).await() }
        }
        return resolveDownloadedFilePath(fileId)
    }

    private suspend fun resolveDownloadedFilePath(fileId: Int?): String? {
        if (fileId == null || fileId == 0) return null
        val file = coRunCatching { gateway.execute(TdApi.GetFile(fileId)) }.getOrNull() ?: return null
        return if (file.local.isDownloadingCompleted) file.local.path.ifEmpty { null } else null
    }

    companion object {
        private const val AVATAR_DOWNLOAD_PRIORITY = 24
        private const val AVATAR_HD_PREFETCH_PRIORITY = 8
        private const val FULL_RES_DOWNLOAD_PRIORITY = 32
        private const val FILE_DOWNLOAD_TIMEOUT_MS = 15_000L
    }
}