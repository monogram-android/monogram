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
import org.monogram.data.gateway.TelegramGateway
import java.io.IOException

@OptIn(UnstableApi::class)
class TelegramStreamingDataSource(
    private val gateway: TelegramGateway,
    private val fileId: Int
) : BaseDataSource(true) {

    class Factory(
        private val gateway: TelegramGateway,
        private val fileId: Int
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            TelegramStreamingDataSource(gateway, fileId)
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

        file = runBlocking { gateway.execute(TdApi.GetFile(fileId)) }
        val f = file ?: throw IOException("File not found for fileId: $fileId")

        val totalSize = kotlin.math.max(f.size.toLong(), f.expectedSize.toLong())
        if (totalSize <= 0) {
            throw IOException("Failed to get file size for fileId: $fileId")
        }

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            totalSize - dataSpec.position
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
                    gateway.execute(
                        TdApi.DownloadFile(
                            fileId,
                            32,
                            0,
                            0,
                            true
                        )
                    )

                    val filePart = gateway.execute(
                        TdApi.ReadFilePart(fileId, position, bytesToFetch.toLong())
                    )

                    internalBuffer = filePart.data
                    bufferOffset = 0
                    bufferLength = internalBuffer?.size ?: 0
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