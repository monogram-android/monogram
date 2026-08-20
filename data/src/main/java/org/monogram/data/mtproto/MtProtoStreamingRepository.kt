package org.monogram.data.mtproto

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import org.monogram.domain.models.FileDownloadEvent

internal class MtProtoStreamingRepository(
    private val files: MtProtoFileRepository,
) {
    fun getDownloadProgress(fileId: Int): Flow<Float> {
        require(fileId > 0) { "MTProto file ID must be positive" }
        return files.fileDownloadFlow
            .filter { it.fileId == fileId }
            .map { event ->
                when (event) {
                    is FileDownloadEvent.Progress -> event.progress
                    is FileDownloadEvent.Completed -> 1f
                    is FileDownloadEvent.Cancelled -> 0f
                }
            }
    }
}
