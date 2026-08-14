package org.monogram.data.infra

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi

class FileObserverHub(
    private val queue: FileDownloadQueue,
    private val fileUpdateHandler: FileUpdateHandler
) {

    data class FileState(
        val fileId: Int,
        val path: String?,
        val isDownloading: Boolean,
        val isDownloaded: Boolean,
        val downloadProgress: Float,
        val isUploading: Boolean,
        val isUploaded: Boolean,
        val uploadProgress: Float
    )

    val fileStates: Flow<FileState> = merge(
        fileUpdateHandler.fileTerminalUpdates.map { it.file },
        fileUpdateHandler.fileProgressUpdates
    )
        .map { emitted -> (queue.getCachedFile(emitted.id) ?: emitted).toState() }
        .distinctUntilChanged()

    fun observeFile(fileId: Int): Flow<FileState> = flow {
        getCachedFile(fileId)?.let { emit(it.toState()) }
        fileStates
            .filter { it.fileId == fileId }
            .collect { emit(it) }
    }.distinctUntilChanged()

    fun observeFiles(fileIds: Set<Int>): Flow<FileState> {
        if (fileIds.isEmpty()) return flow { }
        return flow {
            fileIds.forEach { fileId ->
                getCachedFile(fileId)?.let { emit(it.toState()) }
            }
            fileStates
                .filter { it.fileId in fileIds }
                .collect { emit(it) }
        }.distinctUntilChanged()
    }

    fun getCachedFile(fileId: Int): TdApi.File? = queue.getCachedFile(fileId)

    fun getCachedPath(fileId: Int): String? = queue.getCachedPath(fileId)

    suspend fun awaitDownload(fileId: Int, timeoutMs: Long? = null): Boolean {
        if (queue.getCachedFile(fileId)?.local?.isDownloadingCompleted == true) {
            return true
        }
        return if (timeoutMs == null) {
            runCatching {
                queue.waitForDownload(fileId).await()
                true
            }.getOrDefault(false)
        } else {
            withTimeoutOrNull(timeoutMs) {
                runCatching {
                    queue.waitForDownload(fileId).await()
                    true
                }.getOrDefault(false)
            } ?: false
        }
    }

    private fun TdApi.File.toState(): FileState {
        val localFile = local
        val remoteFile = remote
        val downloadProgress =
            if (size > 0) localFile.downloadedSize.toFloat() / size.toFloat() else 0f
        val uploadProgress =
            if (size > 0) (remoteFile?.uploadedSize ?: 0L).toFloat() / size.toFloat() else 0f
        val path = localFile.path.takeIf { it.isNotEmpty() }
        return FileState(
            fileId = id,
            path = path,
            isDownloading = localFile.isDownloadingActive,
            isDownloaded = localFile.isDownloadingCompleted,
            downloadProgress = downloadProgress,
            isUploading = remoteFile?.isUploadingActive == true,
            isUploaded = remoteFile?.isUploadingCompleted == true,
            uploadProgress = uploadProgress
        )
    }
}
