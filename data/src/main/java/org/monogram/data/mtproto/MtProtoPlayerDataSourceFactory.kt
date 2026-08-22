package org.monogram.data.mtproto

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.FileInputStream
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.monogram.domain.models.FileDownloadEvent
import org.monogram.domain.repository.PlayerDataSourceFactory

/** Creates Media3 data sources backed by registered MTProto file handles. */
internal class MtProtoPlayerDataSourceFactory(
    private val files: MtProtoFileRepository,
) : PlayerDataSourceFactory {
    override fun createPayload(fileId: Int): Any = Factory(files, fileId)

    @OptIn(UnstableApi::class)
    private class Factory(
        private val files: MtProtoFileRepository,
        private val fileId: Int,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = Source(files, fileId)
    }

    @OptIn(UnstableApi::class)
    private class Source(
        private val files: MtProtoFileRepository,
        private val fileId: Int,
    ) : BaseDataSource(true) {
        private var dataSpec: DataSpec? = null
        private var input: FileInputStream? = null
        private var bytesRemaining = 0L
        private var opened = false

        override fun open(dataSpec: DataSpec): Long {
            this.dataSpec = dataSpec
            transferInitializing(dataSpec)
            val localPath = runBlocking {
                files.getInfo(fileId)
                    ?: throw IOException("Unknown MTProto media handle: $fileId")
                // Seek prioritization: bounded specs open once the durable prefix covers
                // [position, position+length); unbounded specs wait for full completion.
                val neededEnd = if (dataSpec.length == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else dataSpec.position + dataSpec.length
                awaitReadablePath(neededEnd).takeIf { it.isNotBlank() }
                    ?: throw IOException("MTProto media download did not produce a local file")
            }
            val stream = try {
                FileInputStream(localPath)
            } catch (error: Exception) {
                throw IOException("Unable to open MTProto media file", error)
            }
            val totalSize = stream.channel.size()
            if (dataSpec.position > totalSize) {
                stream.close()
                throw IOException("MTProto media position exceeds file length")
            }
            stream.channel.position(dataSpec.position)
            input = stream
            bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                totalSize - dataSpec.position
            } else {
                minOf(dataSpec.length, totalSize - dataSpec.position)
            }
            opened = true
            transferStarted(dataSpec)
            return bytesRemaining
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
            val read = input?.read(buffer, offset, minOf(length.toLong(), bytesRemaining).toInt())
                ?: throw IOException("MTProto media source is not open")
            if (read < 0) return C.RESULT_END_OF_INPUT
            bytesRemaining -= read
            bytesTransferred(read)
            return read
        }

        override fun getUri(): Uri? = dataSpec?.uri

        override fun close() {
            runCatching { input?.close() }
            input = null
            dataSpec = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }

        private suspend fun awaitReadablePath(neededEnd: Long): String = coroutineScope {
            val terminal = async(start = CoroutineStart.UNDISPATCHED) {
                files.fileDownloadFlow
                    .filter { it.fileId == fileId }
                    .first { it is FileDownloadEvent.Completed || it is FileDownloadEvent.Cancelled }
            }
            files.download(fileId, offset = 0L, limit = 0L)
            var covered: String? = null
            while (covered == null && !terminal.isCompleted && neededEnd != Long.MAX_VALUE) {
                val event = files.fileDownloadFlow
                    .filter { it.fileId == fileId }
                    .first {
                        it is FileDownloadEvent.Completed ||
                            it is FileDownloadEvent.Cancelled ||
                            (it is FileDownloadEvent.Progress && it.downloadedBytes >= neededEnd)
                    }
                when (event) {
                    is FileDownloadEvent.Completed -> covered = event.path
                    is FileDownloadEvent.Cancelled -> throw IOException("MTProto media download was cancelled")
                    is FileDownloadEvent.Progress -> covered = files.getPath(fileId).takeIf {
                        event.downloadedBytes >= neededEnd
                    } ?: covered
                }
            }
            if (terminal.isCompleted) {
                when (val event = terminal.getCompleted()) {
                    is FileDownloadEvent.Completed -> covered = event.path
                    is FileDownloadEvent.Cancelled -> throw IOException("MTProto media download was cancelled")
                    else -> error("Unexpected MTProto file download event")
                }
            }
            covered ?: throw IOException("MTProto media range never became readable")
        }
    }
}
