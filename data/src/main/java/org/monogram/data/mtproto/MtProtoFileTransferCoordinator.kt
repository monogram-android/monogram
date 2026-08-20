package org.monogram.data.mtproto

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import org.monogram.mtproto.tl.generated.cloud.layer223.InputFileLocation_7d0b23428a
import org.monogram.mtproto.tl.generated.cloud.layer223.upload.FileCdnRedirect
import org.monogram.mtproto.tl.generated.cloud.layer223.upload.File_34a32a2519
import org.monogram.mtproto.tl.generated.cloud.layer223.upload.GetFile

/**
 * Streams an MTProto file into a caller-owned, resumable sink. The sink owns durable bytes and
 * exposes the committed offset, so an interrupted transfer can resume without replaying chunks.
 */
internal class MtProtoFileTransferCoordinator(
    private val transportFactory: MtProtoSessionTransportFactory,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
) {
    init {
        require(chunkSize in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE) {
            "MTProto file chunk size must be between $MIN_CHUNK_SIZE and $MAX_CHUNK_SIZE bytes"
        }
    }

    suspend fun download(
        location: InputFileLocation_7d0b23428a,
        sink: MtProtoFileTransferSink,
    ) {
        var offset = sink.committedOffset()
        require(offset >= 0L) { "MTProto file transfer offset must not be negative" }
        val transport = transportFactory.open(accountSlot)
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val response = transport.execute(
                    GetFile(
                        precise = false,
                        cdnSupported = false,
                        location = location,
                        offset = offset,
                        limit = chunkSize,
                    ),
                )
                val bytes = when (response) {
                    is File_34a32a2519 -> response.bytes.toByteArray()
                    is FileCdnRedirect -> throw UnsupportedOperationException(
                        "MTProto CDN file redirects are not available"
                    )
                }
                if (bytes.isEmpty()) break
                sink.write(offset, bytes)
                offset += bytes.size
                if (bytes.size < chunkSize) break
            }
            sink.complete(offset)
        } finally {
            transport.close()
        }
    }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
        const val MIN_CHUNK_SIZE = 1024
        const val DEFAULT_CHUNK_SIZE = 512 * 1024
        const val MAX_CHUNK_SIZE = 1024 * 1024
    }
}

internal interface MtProtoFileTransferSink {
    suspend fun committedOffset(): Long
    suspend fun write(offset: Long, bytes: ByteArray)
    suspend fun complete(totalBytes: Long)
}
