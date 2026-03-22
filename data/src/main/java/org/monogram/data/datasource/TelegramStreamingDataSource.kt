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

    private var internalBuffer: ByteArray? = null
    private var bufferOffset: Int = 0
    private var bufferLength: Int = 0

    private val PREFETCH_SIZE = 512 * 1024L

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        this.position = dataSpec.position

        transferInitializing(dataSpec)

        file = runBlocking { fileDataSource.getFile(fileId) }
        val f = file ?: throw IOException("File not found for fileId: $fileId")

        val totalSize = kotlin.math.max(f.size, f.expectedSize)
        if (totalSize <= 0) {
            throw IOException("Failed to get file size for fileId: $fileId")
        }

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            totalSize - dataSpec.position
        }

        if (bytesRemaining > 0) {
            runBlocking {
                fileDataSource.downloadFile(
                    fileId = fileId,
                    priority = 16,
                    offset = position,
                    limit = kotlin.math.min(PREFETCH_SIZE, bytesRemaining),
                    synchronous = false
                )
            }
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) return 0
        if (bytesRemaining <= 0L) return C.RESULT_END_OF_INPUT

        if (internalBuffer == null || bufferOffset >= bufferLength) {
            val bytesToFetch = kotlin.math.min(PREFETCH_SIZE, bytesRemaining).toInt()

            runBlocking {
                try {
                    val targetSize = bytesToFetch.toLong()
                    val prefix = fileDataSource.getFileDownloadedPrefixSize(fileId, position)
                    val downloadedPrefix = prefix?.size ?: 0L
                    val availableFromPosition = (downloadedPrefix - position).coerceAtLeast(0L)

                    if (availableFromPosition < targetSize) {
                        fileDataSource.downloadFile(fileId, 24, position, targetSize, synchronous = false)
                        if (availableFromPosition == 0L) {
                            fileDataSource.downloadFile(fileId, 32, position, targetSize, synchronous = true)
                        }
                    }

                    var filePart = fileDataSource.readFilePart(fileId, position, targetSize)
                    if (filePart?.data?.isEmpty() != false) {
                        fileDataSource.downloadFile(fileId, 32, position, targetSize, synchronous = true)
                        filePart = fileDataSource.readFilePart(fileId, position, targetSize)
                    }

                    internalBuffer = filePart?.data
                    bufferOffset = 0
                    bufferLength = internalBuffer?.size ?: 0

                    if (bufferLength > 0 && bytesRemaining > bufferLength) {
                        val nextOffset = position + bufferLength
                        val nextLimit = kotlin.math.min(PREFETCH_SIZE, bytesRemaining - bufferLength)
                        if (nextLimit > 0) {
                            fileDataSource.downloadFile(fileId, 16, nextOffset, nextLimit, synchronous = false)
                        }
                    }
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
        dataSpec = null
        file = null

        internalBuffer = null
        bufferOffset = 0
        bufferLength = 0
    }
}