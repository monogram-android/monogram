package org.monogram.domain.repository

import kotlinx.coroutines.flow.Flow
import org.monogram.domain.models.FileDownloadEvent
import org.monogram.domain.models.FileModel
import org.monogram.domain.models.MessageDownloadEvent

interface FileRepository {
    val fileDownloadFlow: Flow<FileDownloadEvent>
    val messageDownloadFlow: Flow<MessageDownloadEvent>

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