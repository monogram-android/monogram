package org.monogram.data.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull
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

class ProfilePhotoRepositoryImpl(
    private val remote: UserRemoteDataSource,
    private val chatLocal: ChatLocalDataSource,
    private val gateway: TelegramGateway,
    private val fileQueue: FileDownloadQueue,
    private val fileObserverHub: FileObserverHub
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

    override fun getUserProfilePhotosFlow(userId: Long): Flow<List<String>> = channelFlow {
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

    override fun getChatProfilePhotosFlow(chatId: Long): Flow<List<String>> = channelFlow {
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
        limit: Int = 10,
        ensureFullRes: Boolean = false
    ): Pair<List<String>, Set<Int>> {
        if (userId <= 0) return emptyList<String>() to emptySet()
        val trackedFileIds = linkedSetOf<Int>()
        val result = remote.getUserProfilePhotos(userId, offset, limit)
            ?: return emptyList<String>() to emptySet()
        val paths = coroutineScope {
            result.photos
                .map { photo ->
                    async {
                        resolveUserProfilePhotoPath(
                            photo,
                            ensureFullRes,
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
        limit: Int = 10,
        ensureFullRes: Boolean = false
    ): Pair<List<String>, Set<Int>> {
        if (chatId == 0L) return emptyList<String>() to emptySet()
        val trackedFileIds = linkedSetOf<Int>()
        val paths = loadChatPhotoHistoryPaths(chatId, offset, limit, ensureFullRes, trackedFileIds)
        if (paths.isNotEmpty()) return paths to trackedFileIds

        val currentPath = resolveCurrentChatPhotoPath(chatId, ensureFullRes, trackedFileIds)
        return listOfNotNull(currentPath) to trackedFileIds
    }

    private suspend fun loadChatPhotoHistoryPaths(
        chatId: Long,
        offset: Int,
        limit: Int,
        ensureFullRes: Boolean,
        trackedFileIds: MutableSet<Int>? = null
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
                .map { photo ->
                    async {
                        resolveUserProfilePhotoPath(
                            photo,
                            ensureFullRes,
                            trackedFileIds
                        )
                    }
                }
                .awaitAll()
                .filterNotNull()
                .distinct()
        }
    }

    private suspend fun resolveCurrentChatPhotoPath(
        chatId: Long,
        ensureFullRes: Boolean,
        trackedFileIds: MutableSet<Int>? = null
    ): String? {
        val chat = remote.getChat(chatId)?.also { chatLocal.insertChat(it.toEntity()) }
            ?: chatLocal.getChat(chatId)?.toTdApiChat()
            ?: return null
        return resolveChatPhotoInfoPath(chat.photo, ensureFullRes, trackedFileIds)
    }

    private suspend fun resolveChatPhotoInfoPath(
        photoInfo: TdApi.ChatPhotoInfo?,
        ensureFullRes: Boolean,
        trackedFileIds: MutableSet<Int>? = null
    ): String? {
        val smallId = photoInfo?.small?.id?.takeIf { it != 0 }
        val bigId = photoInfo?.big?.id?.takeIf { it != 0 }
        smallId?.let { trackedFileIds?.add(it) }
        bigId?.let { trackedFileIds?.add(it) }
        val preferredFile = if (ensureFullRes) {
            photoInfo?.big ?: photoInfo?.small
        } else {
            photoInfo?.small ?: photoInfo?.big
        } ?: return null

        val directPath = preferredFile.local.path.takeIf { isValidFilePath(it) }
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
        ensureFullRes: Boolean,
        trackedFileIds: MutableSet<Int>? = null
    ): String? {
        val animationFile = photo.animation?.file
        val animationFileId = animationFile?.id?.takeIf { it != 0 }
        animationFileId?.let { trackedFileIds?.add(it) }
        val animationPath = animationFile?.local?.path?.takeIf { isValidFilePath(it) }
        if (animationPath != null) return animationPath
        val downloadedAnimationPath = resolveDownloadedFilePath(animationFileId)
        if (downloadedAnimationPath != null) return downloadedAnimationPath

        if (animationFileId != null) {
            if (!ensureFullRes) {
                fileQueue.enqueue(
                    fileId = animationFileId,
                    priority = avatarDownloadPriority,
                    type = FileDownloadQueue.DownloadType.DEFAULT,
                    synchronous = false
                )
            } else {
                fileQueue.enqueue(
                    fileId = animationFileId,
                    priority = FULL_RES_DOWNLOAD_PRIORITY,
                    type = FileDownloadQueue.DownloadType.DEFAULT,
                    synchronous = false,
                    ignoreSuppression = true
                )
                withTimeoutOrNull(FILE_DOWNLOAD_TIMEOUT_MS) {
                    coRunCatching { fileQueue.waitForDownload(animationFileId).await() }
                }
                resolveDownloadedFilePath(animationFileId)?.let { return it }
            }
        }

        photo.sizes.forEach { size ->
            size.photo.id.takeIf { it != 0 }?.let { trackedFileIds?.add(it) }
        }

        val bestPhotoFile = photo.sizes
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?.photo
            ?: photo.sizes.lastOrNull()?.photo
            ?: return null

        val directPath = bestPhotoFile.local.path.takeIf { isValidFilePath(it) }
        if (directPath != null) return directPath

        if (!ensureFullRes) {
            val fallbackFile = photo.sizes.find { it.type == "m" }?.photo
                ?: photo.sizes.find { it.type == "s" }?.photo
                ?: photo.sizes.find { it.type == "c" }?.photo
                ?: photo.sizes.find { it.type == "b" }?.photo
                ?: photo.sizes.find { it.type == "a" }?.photo
                ?: photo.sizes.firstOrNull()?.photo

            val fallbackDirectPath = fallbackFile?.local?.path?.takeIf { isValidFilePath(it) }
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
        private const val AVATAR_HD_PREFETCH_PRIORITY = 8
        private const val FULL_RES_DOWNLOAD_PRIORITY = 32
        private const val FILE_DOWNLOAD_TIMEOUT_MS = 15_000L
    }
}
