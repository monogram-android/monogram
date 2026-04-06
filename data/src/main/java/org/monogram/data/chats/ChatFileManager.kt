package org.monogram.data.chats

import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import org.monogram.core.DispatcherProvider
import org.monogram.core.ScopeProvider
import org.monogram.data.core.coRunCatching
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.infra.FileDownloadQueue
import org.monogram.data.infra.FileUpdateHandler
import java.util.*
import java.util.concurrent.ConcurrentHashMap


class ChatFileManager(
    private val gateway: TelegramGateway,
    private val dispatchers: DispatcherProvider,
    private val fileQueue: FileDownloadQueue,
    private val fileUpdateHandler: FileUpdateHandler,
    scopeProvider: ScopeProvider,
    private val onUpdate: () -> Unit
) {
    private val scope = scopeProvider.appScope

    private val downloadingFiles: MutableSet<Int> = Collections.newSetFromMap(ConcurrentHashMap())
    private val loadingEmojis: MutableSet<Long> = Collections.newSetFromMap(ConcurrentHashMap())
    private val filePaths = ConcurrentHashMap<Int, String>()
    private val chatPhotoIds = ConcurrentHashMap<Int, Long>()
    private val trackedFileIds = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    fun getFilePath(fileId: Int): String? = filePaths[fileId]
    fun getEmojiPath(emojiId: Long): String? = fileUpdateHandler.customEmojiPaths[emojiId]
    fun getChatIdByPhotoId(fileId: Int): Long? = chatPhotoIds[fileId]

    fun registerChatPhoto(fileId: Int, chatId: Long) {
        chatPhotoIds[fileId] = chatId
    }

    fun registerTrackedFile(fileId: Int) {
        if (fileId != 0) trackedFileIds.add(fileId)
    }

    fun handleFileUpdate(file: TdApi.File): Boolean {
        if (file.local.isDownloadingCompleted) {
            filePaths[file.id] = file.local.path
            return handleFileUpdated(file.id, file.local.path)
        }
        return false
    }

    private fun handleFileUpdated(fileId: Int, path: String): Boolean {
        if (path.isEmpty()) return false
        var updated = false
        fileUpdateHandler.fileIdToCustomEmojiId[fileId]?.let { emojiId ->
            fileUpdateHandler.customEmojiPaths[emojiId] = path
            updated = true
        }
        if (chatPhotoIds.containsKey(fileId)) updated = true
        if (trackedFileIds.remove(fileId)) updated = true
        return updated
    }

    fun downloadFile(fileId: Int, priority: Int, offset: Long = 0, limit: Long = 0, synchronous: Boolean = true) {
        if (fileId == 0) return
        val effectivePriority = if (priority <= 1) 16 else priority
        fileQueue.enqueue(fileId, effectivePriority, FileDownloadQueue.DownloadType.DEFAULT, offset, limit, synchronous)
        if (synchronous) {
            scope.launch(dispatchers.io) {
                coRunCatching {
                    fileQueue.waitForDownload(fileId).await()
                }
            }
        }
    }

    fun loadEmoji(emojiId: Long) {
        if (emojiId == 0L || fileUpdateHandler.customEmojiPaths.containsKey(emojiId)) return
        if (loadingEmojis.add(emojiId)) {
            scope.launch(dispatchers.io) {
                coRunCatching {
                    val result = gateway.execute(TdApi.GetCustomEmojiStickers(longArrayOf(emojiId)))
                    val sticker = result.stickers.firstOrNull() ?: return@launch
                    val file = sticker.sticker
                    val path = file.local.path.ifEmpty { filePaths[file.id] ?: "" }
                    fileUpdateHandler.fileIdToCustomEmojiId[file.id] = emojiId
                    if (path.isNotEmpty()) {
                        fileUpdateHandler.customEmojiPaths[emojiId] = path
                        onUpdate()
                    } else {
                        downloadFile(file.id, 32)
                    }
                }
                loadingEmojis.remove(emojiId)
            }
        }
    }
}