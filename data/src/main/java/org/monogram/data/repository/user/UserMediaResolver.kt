package org.monogram.data.repository.user

import org.drinkless.tdlib.TdApi
import org.monogram.data.core.coRunCatching
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.infra.FileDownloadQueue
import java.util.concurrent.ConcurrentHashMap

internal class UserMediaResolver(
    private val gateway: TelegramGateway,
    private val fileQueue: FileDownloadQueue,
    val emojiPathCache: ConcurrentHashMap<Long, String> = ConcurrentHashMap(),
    val fileIdToUserIdMap: ConcurrentHashMap<Int, Long> = ConcurrentHashMap()
) {
    private val avatarDownloadPriority = AVATAR_DOWNLOAD_PRIORITY
    private val avatarHdPrefetchPriority = AVATAR_HD_PREFETCH_PRIORITY

    suspend fun resolveEmojiPath(user: TdApi.User): String? {
        val emojiId = user.extractEmojiStatusId()
        if (emojiId == 0L) return null

        emojiPathCache[emojiId]?.let { return it }

        return try {
            val result = gateway.execute(TdApi.GetCustomEmojiStickers(longArrayOf(emojiId)))
            if (result is TdApi.Stickers && result.stickers.isNotEmpty()) {
                val file = result.stickers.first().sticker
                if (file.local.isDownloadingCompleted && file.local.path.isNotEmpty()) {
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
                            ?.takeIf { it.isNotEmpty() }
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
        val bigPhoto = user.profilePhoto?.big
        val smallPhoto = user.profilePhoto?.small
        val bigDirectPath = bigPhoto?.local?.path?.ifEmpty { null }
        if (bigDirectPath != null) return bigDirectPath

        val smallDirectPath = smallPhoto?.local?.path?.ifEmpty { null }
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

    private suspend fun resolveDownloadedFilePath(fileId: Int?): String? {
        if (fileId == null || fileId == 0) return null
        val file = coRunCatching { gateway.execute(TdApi.GetFile(fileId)) }.getOrNull() ?: return null
        return if (file.local.isDownloadingCompleted) file.local.path.ifEmpty { null } else null
    }

    companion object {
        private const val AVATAR_DOWNLOAD_PRIORITY = 24
        private const val AVATAR_HD_PREFETCH_PRIORITY = 8
    }
}

internal fun TdApi.User.extractEmojiStatusId(): Long {
    return when (val type = this.emojiStatus?.type) {
        is TdApi.EmojiStatusTypeCustomEmoji -> type.customEmojiId
        is TdApi.EmojiStatusTypeUpgradedGift -> type.modelCustomEmojiId
        else -> 0L
    }
}