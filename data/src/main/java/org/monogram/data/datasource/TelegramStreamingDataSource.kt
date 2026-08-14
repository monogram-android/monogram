package org.monogram.data.datasource

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import org.monogram.data.infra.FileDownloadQueue
import java.io.IOException

@OptIn(UnstableApi::class)
class TelegramStreamingDataSource(
    private val fileDataSource: FileDataSource,
    private val fileId: Int
) : BaseDataSource(true) {

    class Factory(
        private val fileDataSource: FileDataSource,
        private val fileId: Int
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            TelegramStreamingDataSource(fileDataSource, fileId)
    }

    private var dataSpec: DataSpec? = null
    private var file: TdApi.File? = null
    private var position: Long = 0
    private var bytesRemaining: Long = 0
    private var opened = false
    private var demandAcquired = false

    private var internalBuffer: ByteArray? = null
    private var bufferOffset: Int = 0
    private var bufferLength: Int = 0

    private val PREFETCH_SIZE = 512 * 1024L
    private val PREFIX_WAIT_TIMEOUT_MS = 5_000L

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        this.position = dataSpec.position

        transferInitializing(dataSpec)
        try {
            if (!fileDataSource.acquireStreamingDemand(fileId)) {
                throw IOException("Streaming demand rejected for fileId: $fileId")
            }
            demandAcquired = true

            file = runBlocking { fileDataSource.getFile(fileId) }
            val f = file ?: throw IOException("File not found for fileId: $fileId")
            val totalSize = kotlin.math.max(f.size, f.expectedSize)
            if (totalSize <= 0) {
                throw IOException("Failed to get file size for fileId: $fileId")
            }

            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                (totalSize - dataSpec.position).coerceAtLeast(0L)
            }

            opened = true
            transferStarted(dataSpec)
            return bytesRemaining
        } catch (error: Exception) {
            releaseStreamingDemand()
            this.dataSpec = null
            file = null
            if (error is IOException) throw error
            throw IOException("Failed to open streaming file $fileId", error)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) return 0
        if (bytesRemaining <= 0L) return C.RESULT_END_OF_INPUT

        if (internalBuffer == null || bufferOffset >= bufferLength) {
            val bytesToFetch = kotlin.math.min(PREFETCH_SIZE, bytesRemaining).toInt()

            runBlocking {
                try {
                    val targetSize = bytesToFetch.toLong()
                    val range = fileDataSource.downloadStreamingRange(
                        fileId = fileId,
                        priority = 24,
                        offset = position,
                        limit = targetSize,
                        timeoutMs = PREFIX_WAIT_TIMEOUT_MS
                    )
                    if (range.outcome != FileDownloadQueue.StreamingRangeOutcome.COMPLETED) {
                        throw IOException(
                            "Streaming range ${range.outcome.name.lowercase()} for fileId: $fileId"
                        )
                    }

                    val filePart = fileDataSource.readFilePart(fileId, position, targetSize)
                    if (filePart?.data?.isEmpty() != false) {
                        throw IOException("Streaming range completed without readable data for fileId: $fileId")
                    }

                    internalBuffer = filePart.data
                    bufferOffset = 0
                    bufferLength = internalBuffer?.size ?: 0
                } catch (error: IOException) {
                    throw error
                } catch (e: Exception) {
                    throw IOException("Error reading file part: ${e.message}", e)
                }
            }
        }

        if (bufferLength == 0 || internalBuffer == null) return C.RESULT_END_OF_INPUT

        val bytesToRead = kotlin.math.min(readLength, bufferLength - bufferOffset)
        System.arraycopy(internalBuffer!!, bufferOffset, buffer, offset, bytesToRead)

        bufferOffset += bytesToRead
        position += bytesToRead
        bytesRemaining -= bytesToRead
        bytesTransferred(bytesToRead)

        return bytesToRead
    }

    override fun getUri(): Uri? = dataSpec?.uri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        releaseStreamingDemand()
        dataSpec = null
        file = null

        internalBuffer = null
        bufferOffset = 0
        bufferLength = 0
    }

    private fun releaseStreamingDemand() {
        if (!demandAcquired) return
        demandAcquired = false
        fileDataSource.releaseStreamingDemand(fileId)
    }
}
