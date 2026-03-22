package org.monogram.data.datasource

import kotlinx.coroutines.CompletableDeferred
import org.drinkless.tdlib.TdApi

interface FileDataSource {
    suspend fun downloadFile(fileId: Int, priority: Int, offset: Long, limit: Long, synchronous: Boolean): TdApi.File?
    suspend fun cancelDownload(fileId: Int): TdApi.Ok?
    suspend fun getFile(fileId: Int): TdApi.File?
    suspend fun getFileDownloadedPrefixSize(fileId: Int, offset: Long): TdApi.FileDownloadedPrefixSize?
    suspend fun readFilePart(fileId: Int, offset: Long, count: Long): TdApi.Data?
    fun waitForUpload(fileId: Int): CompletableDeferred<Unit>
}
