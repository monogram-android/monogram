package org.monogram.data.repository.user

import org.drinkless.tdlib.TdApi
import org.monogram.data.core.coRunCatching
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.infra.FileDownloadQueue
import org.monogram.data.mapper.isValidFilePath
import java.util.concurrent.ConcurrentHashMap

internal class UserMediaResolver(
    private val gateway: TelegramGateway,
    private val fileQueue: FileDownloadQueue,
    val emojiPathCache: ConcurrentHashMap<Long, String> = ConcurrentHashMap(),
    val fileIdToUserIdMap: ConcurrentHashMap<Int, Long> = ConcurrentHashMap()
) {
    private val avatarDownloadPriority = AVATAR_DOWNLOAD_PRIORITY
    private val avatarHdPrefetchPriority = AVATAR_HD_PREFETCH_PRIORITY
    private val animatedAvatarPathCache = ConcurrentHashMap<Long, CachedAnimatedAvatar>()
    private val animatedAvatarLookupCache = ConcurrentHashMap<Long, AnimatedAvatarLookup>()

    suspend fun resolveEmojiPath(user: TdApi.User): String? {
        val emojiId = user.extractEmojiStatusId()
        if (emojiId == 0L) return null

        emojiPathCache[emojiId]?.let { return it }

        return try {
            val result = gateway.execute(TdApi.GetCustomEmojiStickers(longArrayOf(emojiId)))
            if (result is TdApi.Stickers && result.stickers.isNotEmpty()) {
                val file = result.stickers.first().sticker
                if (file.local.isDownloadingCompleted && isValidFilePath(file.local.path)) {
                    emojiPathCache[emojiId] = file.local.path
                    file.local.path
                } else {
                    fileIdToUserIdMap[file.id] = user.id
                    fileQueue.enqueue(file.id, 1, FileDownloadQueue.DownloadType.DEFAULT, synchronous = false)
                    coRunCatching { fileQueue.waitForDownload(file.id).await() }

                    val refreshedPath = coRunCatching {
                        (gateway.execute(TdApi.GetFile(file.id)) as? TdApi.File)
                            ?.local
                            ?.path
                            ?.takeIf { isValidFilePath(it) }
                    }.getOrNull()
                    if (refreshedPath != null) {
                        emojiPathCache[emojiId] = refreshedPath
                    }
                    refreshedPath
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun resolveAvatarPath(user: TdApi.User): String? {
        resolveAnimatedAvatarPath(user)?.let { return it }

        val bigPhoto = user.profilePhoto?.big
        val smallPhoto = user.profilePhoto?.small
        val bigDirectPath = bigPhoto?.local?.path?.takeIf { isValidFilePath(it) }
        if (bigDirectPath != null) return bigDirectPath

        val smallDirectPath = smallPhoto?.local?.path?.takeIf { isValidFilePath(it) }
        if (smallDirectPath != null) {
            val bigId = bigPhoto?.id?.takeIf { it != 0 }
            if (bigId != null && bigId != smallPhoto.id) {
                fileQueue.enqueue(
                    bigId,
                    avatarHdPrefetchPriority,
                    FileDownloadQueue.DownloadType.DEFAULT,
                    synchronous = false
                )
            }
            return smallDirectPath
        }

        val resolvedSmallPath = resolveDownloadedFilePath(smallPhoto?.id)
        if (resolvedSmallPath != null) {
            val bigId = bigPhoto?.id?.takeIf { it != 0 }
            if (bigId != null && bigId != smallPhoto?.id) {
                fileQueue.enqueue(
                    bigId,
                    avatarHdPrefetchPriority,
                    FileDownloadQueue.DownloadType.DEFAULT,
                    synchronous = false
                )
            }
            return resolvedSmallPath
        }

        val resolvedBigPath = resolveDownloadedFilePath(bigPhoto?.id)
        if (resolvedBigPath != null) return resolvedBigPath

        val smallId = smallPhoto?.id?.takeIf { it != 0 }
        val bigId = bigPhoto?.id?.takeIf { it != 0 }
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

    private suspend fun resolveAnimatedAvatarPath(user: TdApi.User): String? {
        val profilePhoto = user.profilePhoto ?: return null
        if (!profilePhoto.hasAnimation || user.id <= 0L) return null

        animatedAvatarPathCache[user.id]
            ?.takeIf { it.photoId == profilePhoto.id && isValidFilePath(it.path) }
            ?.let { return it.path }

        animatedAvatarLookupCache[user.id]
            ?.takeIf { it.photoId == profilePhoto.id }
            ?.let { lookup ->
                resolveAnimationPath(
                    user.id,
                    profilePhoto.id,
                    lookup.animationFileIds
                )?.let { return it }
            }

        val photos = coRunCatching {
            (gateway.execute(
                TdApi.GetUserProfilePhotos(
                    user.id,
                    0,
                    USER_PROFILE_PHOTO_LOOKUP_LIMIT
                )
            ) as? TdApi.ChatPhotos)?.photos.orEmpty()
        }.getOrDefault(emptyArray())

        val currentPhoto = photos.firstOrNull { it.id == profilePhoto.id }
            ?: photos.firstOrNull()
            ?: return null

        val animationFileIds = buildList {
            currentPhoto.smallAnimation?.file?.id?.takeIf { it != 0 }?.let(::add)
            currentPhoto.animation?.file?.id
                ?.takeIf { it != 0 && it !in this }
                ?.let(::add)
        }

        if (animationFileIds.isEmpty()) return null

        animatedAvatarLookupCache[user.id] = AnimatedAvatarLookup(
            photoId = currentPhoto.id,
            animationFileIds = animationFileIds
        )

        currentPhoto.smallAnimation?.file?.local?.path
            ?.takeIf { isValidFilePath(it) }
            ?.also { cacheAnimatedAvatarPath(user.id, currentPhoto.id, it) }
            ?.let { return it }

        currentPhoto.animation?.file?.local?.path
            ?.takeIf { isValidFilePath(it) }
            ?.also { cacheAnimatedAvatarPath(user.id, currentPhoto.id, it) }
            ?.let { return it }

        return resolveAnimationPath(user.id, currentPhoto.id, animationFileIds)
    }

    private suspend fun resolveAnimationPath(
        userId: Long,
        photoId: Long,
        animationFileIds: List<Int>
    ): String? {
        animationFileIds.forEach { fileId ->
            resolveDownloadedFilePath(fileId)
                ?.also { cacheAnimatedAvatarPath(userId, photoId, it) }
                ?.let { return it }
        }

        animationFileIds.firstOrNull()?.let { primaryFileId ->
            fileIdToUserIdMap[primaryFileId] = userId
            fileQueue.enqueue(
                primaryFileId,
                avatarDownloadPriority,
                FileDownloadQueue.DownloadType.DEFAULT,
                synchronous = false
            )
        }

        animationFileIds.drop(1).forEach { secondaryFileId ->
            fileIdToUserIdMap[secondaryFileId] = userId
            fileQueue.enqueue(
                secondaryFileId,
                avatarHdPrefetchPriority,
                FileDownloadQueue.DownloadType.DEFAULT,
                synchronous = false
            )
        }

        return null
    }

    private fun cacheAnimatedAvatarPath(userId: Long, photoId: Long, path: String) {
        animatedAvatarPathCache[userId] = CachedAnimatedAvatar(photoId = photoId, path = path)
    }

    private suspend fun resolveDownloadedFilePath(fileId: Int?): String? {
        if (fileId == null || fileId == 0) return null
        val file = coRunCatching { gateway.execute(TdApi.GetFile(fileId)) }.getOrNull() ?: return null
        return if (file.local.isDownloadingCompleted) {
            file.local.path.takeIf { isValidFilePath(it) }
        } else {
            null
        }
    }

    companion object {
        private const val AVATAR_DOWNLOAD_PRIORITY = 24
        private const val AVATAR_HD_PREFETCH_PRIORITY = 8
        private const val USER_PROFILE_PHOTO_LOOKUP_LIMIT = 10
    }
}

private data class CachedAnimatedAvatar(
    val photoId: Long,
    val path: String
)

private data class AnimatedAvatarLookup(
    val photoId: Long,
    val animationFileIds: List<Int>
)

internal fun TdApi.User.extractEmojiStatusId(): Long {
    return when (val type = this.emojiStatus?.type) {
        is TdApi.EmojiStatusTypeCustomEmoji -> type.customEmojiId
        is TdApi.EmojiStatusTypeUpgradedGift -> type.modelCustomEmojiId
        else -> 0L
    }
}