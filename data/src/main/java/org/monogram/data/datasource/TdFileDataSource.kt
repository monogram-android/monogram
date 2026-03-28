package org.monogram.data.datasource

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import org.drinkless.tdlib.TdApi
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.infra.FileDownloadQueue

class TdFileDataSource(
    private val gateway: TelegramGateway,
    private val fileDownloadQueue: FileDownloadQueue
) : FileDataSource {
    override suspend fun downloadFile(fileId: Int, priority: Int, offset: Long, limit: Long, synchronous: Boolean): TdApi.File?  {
        Log.d(
            "DownloadDebug",
            "tdFile.downloadFile: fileId=$fileId priority=$priority offset=$offset limit=$limit sync=$synchronous"
        )
        fileDownloadQueue.clearSuppression(fileId)
        fileDownloadQueue.enqueue(
            fileId,
            priority,
            FileDownloadQueue.DownloadType.DEFAULT,
            offset,
            limit,
            synchronous,
            ignoreSuppression = true
        )
        if (synchronous) {
            runCatching { fileDownloadQueue.waitForDownload(fileId).await() }
        }
        return getFile(fileId)
    }

    override suspend fun cancelDownload(fileId: Int): TdApi.Ok? {
        Log.d("DownloadDebug", "tdFile.cancelDownload: fileId=$fileId")
        fileDownloadQueue.cancelDownload(fileId, force = true)
        val result = gateway.execute(TdApi.CancelDownloadFile(fileId, false))
        Log.d("DownloadDebug", "tdFile.cancelDownload.result: fileId=$fileId ok=${result is TdApi.Ok}")
        return if (result is TdApi.Ok) result else null
    }

    override suspend fun getFile(fileId: Int): TdApi.File? {
        return runCatching { gateway.execute(TdApi.GetFile(fileId)) }.getOrNull()
    }

    override suspend fun getFileDownloadedPrefixSize(fileId: Int, offset: Long): TdApi.FileDownloadedPrefixSize? {
        return runCatching { gateway.execute(TdApi.GetFileDownloadedPrefixSize(fileId, offset)) }.getOrNull()
    }

    override suspend fun readFilePart(fileId: Int, offset: Long, count: Long): TdApi.Data? {
        return runCatching { gateway.execute(TdApi.ReadFilePart(fileId, offset, count)) }.getOrNull()
    }

    override fun waitForUpload(fileId: Int): CompletableDeferred<Unit> {
        return fileDownloadQueue.waitForUpload(fileId)
    }
}