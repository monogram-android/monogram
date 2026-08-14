package org.monogram.data.infra

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import org.drinkless.tdlib.TdApi
import org.monogram.data.gateway.UpdateDispatcher
import java.util.concurrent.ConcurrentHashMap

interface FileUpdateQueue {
    fun updateFileCache(file: TdApi.File)
    fun getCachedFile(fileId: Int): TdApi.File?
    fun notifyDownloadComplete(fileId: Int)
    fun notifyUploadComplete(fileId: Int)
}

internal data class FileTerminalUpdate(
    val file: TdApi.File,
    val downloadCompleted: Boolean,
    val downloadCancelled: Boolean,
    val uploadCompleted: Boolean
)

class FileUpdateHandler(
    private val registry: FileMessageRegistry,
    private val queue: FileUpdateQueue,
    private val updates: UpdateDispatcher,
    private val scope: CoroutineScope
) {
    val customEmojiPaths = SynchronizedLruMap<Long, String>(CUSTOM_EMOJI_CACHE_SIZE)
    val fileIdToCustomEmojiId = SynchronizedLruMap<Int, Long>(FILE_TO_EMOJI_CACHE_SIZE)

    private val completedDownloadIds = ConcurrentHashMap.newKeySet<Int>()
    private val downloadingFileIds = ConcurrentHashMap.newKeySet<Int>()
    private val _downloadProgress = LatestByKeyEventFlow<Long, Pair<Long, Float>>(
        scope = scope,
        keyOf = { it.first }
    )
    private val downloadCompletions = OrderedEventFlow<Pair<Long, String>>(scope)
    private val _fileDownloadProgress = LatestByKeyEventFlow<Long, Pair<Long, Float>>(
        scope = scope,
        keyOf = { it.first },
        shouldEmit = { (fileId, _) ->
            queue.getCachedFile(fileId.toInt())?.local?.isDownloadingCompleted != true
        }
    )
    private val fileDownloadCompletions = OrderedEventFlow<Pair<Long, String>>(scope)
    private val _uploadProgress = LatestByKeyEventFlow<Long, Pair<Long, Float>>(
        scope = scope,
        keyOf = { it.first }
    )
    private val terminalFileUpdates = OrderedEventFlow<FileTerminalUpdate>(scope)
    private val progressFileUpdates = LatestByKeyEventFlow<Int, TdApi.File>(
        scope = scope,
        keyOf = { it.id },
        shouldEmit = { file ->
            file.local?.isDownloadingActive != true ||
                    queue.getCachedFile(file.id)?.local?.isDownloadingCompleted != true
        }
    )

    val downloadProgress = _downloadProgress.events
    val downloadCompleted = downloadCompletions.events
    val fileDownloadProgress = _fileDownloadProgress.events.filter { (fileId, _) ->
        queue.getCachedFile(fileId.toInt())?.local?.isDownloadingCompleted != true
    }
    val fileDownloadCompleted = fileDownloadCompletions.events
    val uploadProgress = _uploadProgress.events
    internal val fileTerminalUpdates = terminalFileUpdates.events
    internal val fileProgressUpdates = progressFileUpdates.events

    init {
        // The completion/cancellation edge resolves waiters and records the local path, so it
        // stays lossless. Progress is independently conflated by file ID below.
        updates.lane(
            name = "files",
            scope = scope,
            filter = { it is TdApi.UpdateFile },
        ) { update ->
            handle((update as TdApi.UpdateFile).file)
        }
    }

    private fun handle(file: TdApi.File) {
        queue.updateFileCache(file)
        val current = queue.getCachedFile(file.id) ?: file
        if (
            current.local?.isDownloadingCompleted == true &&
            file.local?.isDownloadingActive == true &&
            file.local?.isDownloadingCompleted != true
        ) {
            return
        }

        val downloading = current.local?.isDownloadingActive == true
        val downloadDone = current.local?.isDownloadingCompleted == true
        val uploading = current.remote?.isUploadingActive == true
        val uploadDone = current.remote?.isUploadingCompleted == true
        val wasDownloading = downloadingFileIds.contains(current.id)
        val downloadCancelled = wasDownloading && !downloading && !downloadDone
        val downloadCompletionEdge = downloadDone && completedDownloadIds.add(current.id)

        if (downloading) downloadingFileIds.add(current.id) else downloadingFileIds.remove(current.id)
        if (downloadDone) {
            progressFileUpdates.remove(current.id)
            _fileDownloadProgress.remove(current.id.toLong())
        } else if (!downloading) {
            completedDownloadIds.remove(current.id)
        }

        if (downloadCompletionEdge || downloadCancelled || uploadDone || (!downloading && !uploading)) {
            terminalFileUpdates.enqueue(
                FileTerminalUpdate(current, downloadCompletionEdge, downloadCancelled, uploadDone)
            )
        }
        if (downloading || (uploading && !uploadDone)) {
            progressFileUpdates.enqueue(current)
        }

        if (downloadDone) queue.notifyDownloadComplete(current.id)

        if (uploadDone) queue.notifyUploadComplete(current.id)

        val entries = registry.getMessages(current.id)
        if (entries.isNotEmpty()) {
            if (downloadCompletionEdge) {
                handleCustomEmoji(current.id, current.local?.path ?: "")
                fileDownloadCompletions.enqueue(current.id.toLong() to (current.local?.path ?: ""))
                entries.forEach { (_, msgId) ->
                    _downloadProgress.remove(msgId)
                    downloadCompletions.enqueue(msgId to (current.local?.path ?: ""))
                }
            } else if (downloading && current.id !in completedDownloadIds) {
                val progress =
                    if (current.size > 0) current.local!!.downloadedSize.toFloat() / current.size else 0f
                _fileDownloadProgress.enqueue(current.id.toLong() to progress)
                entries.forEach { (_, msgId) -> _downloadProgress.enqueue(msgId to progress) }
            }
            if (uploadDone) {
                entries.forEach { (_, msgId) -> _uploadProgress.enqueue(msgId to 1f) }
            } else if (uploading) {
                val progress =
                    if (current.size > 0) current.remote!!.uploadedSize.toFloat() / current.size else 0f
                entries.forEach { (_, msgId) -> _uploadProgress.enqueue(msgId to progress) }
            }
        } else if (registry.standaloneFileIds.contains(current.id)) {
            if (downloadCompletionEdge) {
                fileDownloadCompletions.enqueue(current.id.toLong() to (current.local?.path ?: ""))
                downloadCompletions.enqueue(current.id.toLong() to (current.local?.path ?: ""))
                _downloadProgress.remove(current.id.toLong())
                registry.standaloneFileIds.remove(current.id)
            } else if (downloading && current.id !in completedDownloadIds) {
                val progress =
                    if (current.size > 0) current.local!!.downloadedSize.toFloat() / current.size else 0f
                _fileDownloadProgress.enqueue(current.id.toLong() to progress)
                _downloadProgress.enqueue(current.id.toLong() to progress)
            }
        } else {
            if (downloadCompletionEdge) {
                fileDownloadCompletions.enqueue(current.id.toLong() to (current.local?.path ?: ""))
            } else if (downloading && current.id !in completedDownloadIds) {
                val progress =
                    if (current.size > 0) current.local!!.downloadedSize.toFloat() / current.size else 0f
                _fileDownloadProgress.enqueue(current.id.toLong() to progress)
            }
        }
    }

    private fun handleCustomEmoji(fileId: Int, path: String) {
        val emojiId = fileIdToCustomEmojiId[fileId] ?: return
        customEmojiPaths[emojiId] = path
    }

    fun clearMemoryCaches() {
        customEmojiPaths.clear()
        fileIdToCustomEmojiId.clear()
    }

    fun memoryCacheSnapshot(): MemoryCacheSnapshot {
        return MemoryCacheSnapshot(
            customEmojiPathsSize = customEmojiPaths.size(),
            fileToEmojiSize = fileIdToCustomEmojiId.size()
        )
    }

    data class MemoryCacheSnapshot(
        val customEmojiPathsSize: Int,
        val fileToEmojiSize: Int
    )

    companion object {
        private const val CUSTOM_EMOJI_CACHE_SIZE = 512
        private const val FILE_TO_EMOJI_CACHE_SIZE = 512
    }
}
