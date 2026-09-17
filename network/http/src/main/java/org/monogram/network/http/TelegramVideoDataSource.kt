package org.monogram.network.http

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.monogram.core.common.PerfLog
import org.monogram.core.common.Outcome
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

fun interface TelegramChunkFetcher {
    suspend fun fetch(chatId: PeerId, messageId: Int, destPath: String, offset: Long): Outcome<String>
}

/** One aligned 512 KiB part on disk; reads stream from it instead of a heap copy. */
@UnstableApi
class TelegramVideoDataSource private constructor(
    private val message: Message,
    private val directory: File,
    private val fetcher: TelegramChunkFetcher?,
) : BaseDataSource(true) {
    class Factory(
        private val message: Message,
        private val directory: File,
        private val fetcher: TelegramChunkFetcher?,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = TelegramVideoDataSource(message, directory, fetcher)
    }

    private var uri: Uri? = null
    private var position = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var partOffset = -1L
    private var partLength = 0L
    private var partFile: File? = null
    private var partHandle: RandomAccessFile? = null
    @Volatile private var request: Job? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        if (fetcher == null) throw IOException("Media streaming unavailable")
        val size = message.fileSize?.takeIf { it > 0 }
        if (size != null && dataSpec.position > size) throw IOException("Position exceeds media size")
        position = dataSpec.position
        remaining = when {
            size != null && dataSpec.length != C.LENGTH_UNSET.toLong() -> minOf(size - position, dataSpec.length)
            size != null -> size - position
            else -> dataSpec.length
        }
        releasePart()
        request = Job()
        uri = dataSpec.uri
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val active = request ?: throw IOException("Media source closed")
        if (!active.isActive) throw IOException("Media read cancelled")
        if (partOffset < 0 || position < partOffset || position >= partLength) {
            openPart(position / PART_SIZE * PART_SIZE, active)
        }
        if (position >= partLength) {
            if (remaining > 0) throw IOException("Unexpected end of media")
            return C.RESULT_END_OF_INPUT
        }
        val take = if (remaining < 0) Long.MAX_VALUE else remaining
        val count = minOf(length.toLong(), partLength - position, take).toInt().coerceAtLeast(1)
        val handle = partHandle ?: throw IOException("Media part unavailable")
        handle.seek(position)
        val read = handle.read(buffer, offset, count)
        if (read <= 0) {
            if (remaining > 0) throw IOException("Unexpected end of media")
            return C.RESULT_END_OF_INPUT
        }
        position += read
        if (remaining > 0) remaining -= read
        bytesTransferred(read)
        return read
    }

    private fun openPart(offset: Long, active: Job) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create media buffer")
        releasePart()
        val file = File.createTempFile("video-", ".part", directory)
        val started = PerfLog.nowMs()
        try {
            runBlocking(active) {
                when (val result = checkNotNull(fetcher).fetch(message.id.chatId, message.id.id, file.absolutePath, offset)) {
                    is Outcome.Err -> throw IOException(result.message)
                    is Outcome.Ok -> Unit
                }
            }
            val length = file.length()
            if (length <= offset) throw IOException("Empty media part")
            partFile = file
            partLength = length
            partHandle = RandomAccessFile(file, "r")
            partOffset = offset
        } catch (e: Exception) {
            file.delete()
            partOffset = -1
            throw IOException("Media part unavailable: ${e.message}", e)
        } finally {
            if (PerfLog.isEnabled()) {
                PerfLog.mark("stream:part", PerfLog.nowMs() - started, "offset=$offset")
            }
        }
    }

    private fun releasePart() {
        runCatching { partHandle?.close() }
        partHandle = null
        // The file is only unlinked here: an open handle keeps serving already-read bytes.
        runCatching { partFile?.delete() }
        partFile = null
        partLength = 0L
        partOffset = -1
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        request?.cancel()
        request = null
        releasePart()
        if (uri != null) {
            uri = null
            transferEnded()
        }
    }

    companion object {
        const val PART_SIZE = 512 * 1024L
    }
}
