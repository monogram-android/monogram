package org.monogram.data.infra

import org.monogram.core.ScopeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import org.monogram.data.gateway.UpdateDispatcher
import java.util.concurrent.ConcurrentHashMap

class FileUpdateHandler(
    private val registry: FileMessageRegistry,
    private val queue: FileDownloadQueue,
    private val updates: UpdateDispatcher,
    private val scope: ScopeProvider
) {
    val customEmojiPaths = ConcurrentHashMap<Long, String>()
    val fileIdToCustomEmojiId = ConcurrentHashMap<Int, Long>()

    private val _downloadProgress = MutableSharedFlow<Pair<Long, Float>>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _downloadCompleted = MutableSharedFlow<Pair<Long, String>>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _uploadProgress = MutableSharedFlow<Pair<Long, Float>>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val downloadProgress = _downloadProgress.asSharedFlow()
    val downloadCompleted = _downloadCompleted.asSharedFlow()
    val uploadProgress = _uploadProgress.asSharedFlow()

    init {
        scope.appScope.launch {
            updates.file.collect { update -> handle(update.file) }
        }
    }

    private fun handle(file: TdApi.File) {
        queue.updateFileCache(file)

        val downloading = file.local?.isDownloadingActive == true
        val downloadDone = file.local?.isDownloadingCompleted == true
        val uploading = file.remote?.isUploadingActive == true
        val uploadDone = file.remote?.isUploadingCompleted == true

        if (downloadDone) queue.notifyDownloadComplete(file.id)

        if (uploadDone) queue.notifyUploadComplete(file.id)

        val entries = registry.getMessages(file.id)
        if (entries.isNotEmpty()) {
            scope.appScope.launch {
                if (downloadDone) {
                    handleCustomEmoji(file.id, file.local?.path ?: "")
                    entries.forEach { (_, msgId) ->
                        _downloadCompleted.emit(msgId to (file.local?.path ?: ""))
                        _downloadProgress.emit(msgId to 1f)
                    }
                } else if (downloading) {
                    val progress = if (file.size > 0) file.local!!.downloadedSize.toFloat() / file.size else 0f
                    entries.forEach { (_, msgId) -> _downloadProgress.emit(msgId to progress) }
                }
                if (uploadDone) {
                    entries.forEach { (_, msgId) -> _uploadProgress.emit(msgId to 1f) }
                } else if (uploading) {
                    val progress = if (file.size > 0) file.remote!!.uploadedSize.toFloat() / file.size else 0f
                    entries.forEach { (_, msgId) -> _uploadProgress.emit(msgId to progress) }
                }
            }
        } else if (registry.standaloneFileIds.contains(file.id)) {
            scope.appScope.launch {
                if (downloadDone) {
                    _downloadCompleted.emit(file.id.toLong() to (file.local?.path ?: ""))
                    _downloadProgress.emit(file.id.toLong() to 1f)
                    registry.standaloneFileIds.remove(file.id)
                } else if (downloading) {
                    val progress = if (file.size > 0) file.local!!.downloadedSize.toFloat() / file.size else 0f
                    _downloadProgress.emit(file.id.toLong() to progress)
                }
            }
        }
    }

    private fun handleCustomEmoji(fileId: Int, path: String) {
        val emojiId = fileIdToCustomEmojiId[fileId] ?: return
        customEmojiPaths[emojiId] = path
    }
}