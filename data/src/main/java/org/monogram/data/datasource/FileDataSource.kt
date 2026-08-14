package org.monogram.data.datasource

import kotlinx.coroutines.CompletableDeferred
import org.drinkless.tdlib.TdApi
import org.monogram.data.infra.FileDownloadQueue

interface FileDataSource {
    fun acquireStreamingDemand(fileId: Int): Boolean
    fun releaseStreamingDemand(fileId: Int)
    suspend fun downloadStreamingRange(
        fileId: Int,
        priority: Int,
        offset: Long,
        limit: Long,
        timeoutMs: Long
    ): FileDownloadQueue.StreamingRangeResult

    suspend fun downloadFile(
        fileId: Int,
        priority: Int,
        offset: Long,
        limit: Long,
        synchronous: Boolean,
        userInitiated: Boolean = false
    ): TdApi.File?

    suspend fun downloadFile(
        fileId: Int,
        priority: Int,
        offset: Long,
        limit: Long,
        synchronous: Boolean,
        type: FileDownloadQueue.DownloadType,
        userInitiated: Boolean = false
    ): TdApi.File?
    suspend fun cancelDownload(fileId: Int): TdApi.Ok?
    suspend fun getFile(fileId: Int): TdApi.File?
    suspend fun getFileDownloadedPrefixSize(fileId: Int, offset: Long): TdApi.FileDownloadedPrefixSize?
    suspend fun readFilePart(fileId: Int, offset: Long, count: Long): TdApi.Data?
    fun waitForUpload(fileId: Int): CompletableDeferred<Unit>
}
