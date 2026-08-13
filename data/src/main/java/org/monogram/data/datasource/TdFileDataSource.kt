package org.monogram.data.datasource

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi
import org.monogram.data.core.coRunCatching
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.infra.FileDownloadQueue

private const val SYNCHRONOUS_DOWNLOAD_TIMEOUT_MS = 60_000L

class TdFileDataSource(
    private val gateway: TelegramGateway,
    private val fileDownloadQueue: FileDownloadQueue
) : FileDataSource {
    override suspend fun downloadFile(
        fileId: Int,
        priority: Int,
        offset: Long,
        limit: Long,
        synchronous: Boolean,
        userInitiated: Boolean
    ): TdApi.File? {
        return downloadFile(
            fileId = fileId,
            priority = priority,
            offset = offset,
            limit = limit,
            synchronous = synchronous,
            type = FileDownloadQueue.DownloadType.DEFAULT,
            userInitiated = userInitiated
        )
    }

    override suspend fun downloadFile(
        fileId: Int,
        priority: Int,
        offset: Long,
        limit: Long,
        synchronous: Boolean,
        type: FileDownloadQueue.DownloadType,
        userInitiated: Boolean
    ): TdApi.File? {
        fileDownloadQueue.clearSuppression(fileId)
        fileDownloadQueue.enqueue(
            fileId,
            priority,
            type,
            offset,
            limit,
            synchronous,
            ignoreSuppression = true,
            userInitiated = userInitiated
        )
        if (synchronous) {
            // Bounded: the queue can legitimately decline to enqueue (cooldown, suppression),
            // and an unbounded await here left story loads hanging forever when it did.
            coRunCatching {
                withTimeout(SYNCHRONOUS_DOWNLOAD_TIMEOUT_MS) {
                    fileDownloadQueue.waitForDownload(fileId).await()
                }
            }
        }
        return getFile(fileId)
    }

    override suspend fun cancelDownload(fileId: Int): TdApi.Ok? {
        fileDownloadQueue.cancelDownload(fileId, force = true)
        return TdApi.Ok()
    }

    override suspend fun getFile(fileId: Int): TdApi.File? {
        return coRunCatching { gateway.execute(TdApi.GetFile(fileId)) }.getOrNull()
    }

    override suspend fun getFileDownloadedPrefixSize(fileId: Int, offset: Long): TdApi.FileDownloadedPrefixSize? {
        return coRunCatching { gateway.execute(TdApi.GetFileDownloadedPrefixSize(fileId, offset)) }.getOrNull()
    }

    override suspend fun readFilePart(fileId: Int, offset: Long, count: Long): TdApi.Data? {
        return coRunCatching { gateway.execute(TdApi.ReadFilePart(fileId, offset, count)) }.getOrNull()
    }

    override fun waitForUpload(fileId: Int): CompletableDeferred<Unit> {
        return fileDownloadQueue.waitForUpload(fileId)
    }
}