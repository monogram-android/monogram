package org.monogram.domain.repository

import kotlinx.coroutines.flow.Flow
import org.monogram.domain.models.FileModel

interface FileRepository {
    val messageDownloadProgressFlow: Flow<Pair<Long, Float>>
    val messageDownloadCancelledFlow: Flow<Long>
    val messageDownloadCompletedFlow: Flow<Triple<Long, Int, String>>

    fun downloadFile(
        fileId: Int,
        priority: Int = 1,
        offset: Long = 0,
        limit: Long = 0,
        synchronous: Boolean = false
    )

    suspend fun cancelDownloadFile(fileId: Int)

    suspend fun getFilePath(fileId: Int): String?

    suspend fun getFileInfo(fileId: Int): FileModel?
}