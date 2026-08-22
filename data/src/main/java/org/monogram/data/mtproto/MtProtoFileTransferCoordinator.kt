package org.monogram.data.mtproto

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import org.monogram.mtproto.tl.generated.cloud.layer223.FileHash_be7ffe4837
import org.monogram.mtproto.tl.generated.cloud.layer223.InputFileLocation_7d0b23428a
import org.monogram.mtproto.tl.generated.cloud.layer223.upload.FileCdnRedirect
import org.monogram.mtproto.tl.generated.cloud.layer223.upload.File_34a32a2519
import org.monogram.mtproto.tl.generated.cloud.layer223.upload.GetFile
import org.monogram.mtproto.tl.generated.cloud.layer223.upload.ReuploadCdnFile
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.tl.runtime.TlObject
import org.monogram.mtproto.transport.MtProtoRpcTransport
import org.monogram.mtproto.upload.MtProtoCdnChunkDecryptor
import org.monogram.mtproto.upload.MtProtoCdnFileReader
import org.monogram.mtproto.upload.MtProtoCdnReadResult

/** Opens one CDN-DC transport for a redirect DC id, reusing the home auth key. */
internal fun interface MtProtoCdnTransportFactory {
    suspend fun open(dcId: Int): MtProtoRpcTransport
}

/**
 * Streams an MTProto file into a caller-owned, resumable sink. The sink owns durable bytes and
 * exposes the committed offset, so an interrupted transfer can resume without replaying chunks.
 *
 * CDN redirects are followed transparently: chunks are read from the redirect DC, decrypted with
 * AES-CTR and verified against the advertised file hashes; `cdnFileReuploadNeeded` triggers a
 * main-DC `upload.reuploadCdnFile` whose fresh hash ranges replace the stale ones.
 */
internal class MtProtoFileTransferCoordinator(
    private val transportFactory: MtProtoSessionTransportFactory,
    private val cdnTransportFactory: MtProtoCdnTransportFactory? = null,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
) {
    init {
        require(chunkSize in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE) {
            "MTProto file chunk size must be between $MIN_CHUNK_SIZE and $MAX_CHUNK_SIZE bytes"
        }
    }

    private class CdnSession(val transport: MtProtoRpcTransport, redirect: FileCdnRedirect) {
        val fileToken: ByteArray = redirect.fileToken.toByteArray()
        private val key = redirect.encryptionKey.toByteArray()
        private val iv = redirect.encryptionIv.toByteArray()
        var hashes: List<FileHash_be7ffe4837> = redirect.fileHashes.filterIsInstance<FileHash_be7ffe4837>()
            .sortedBy { it.offset }

        val reader: MtProtoCdnFileReader by lazy {
            MtProtoCdnFileReader(
                cdnExecutor = { method -> transport.execute(method as TlMethod<TlObject>) },
                decryptor = decryptor(),
                fileToken = fileToken,
            )
        }

        /** Replaces every hash range overlapped by the reuploaded ranges. */
        fun replaceHashes(incoming: List<FileHash_be7ffe4837>) {
            var ranges = hashes
            for (range in incoming) {
                ranges = ranges.filterNot {
                    it.offset < range.offset + range.limit && range.offset < it.offset + it.limit
                } + range
            }
            hashes = ranges.distinct().sortedBy { it.offset }
        }

        fun hashRangeContaining(offset: Long) =
            hashes.firstOrNull { offset >= it.offset && offset < it.offset + it.limit }

        fun refreshedReader(): MtProtoCdnFileReader = MtProtoCdnFileReader(
            cdnExecutor = { method -> transport.execute(method as TlMethod<TlObject>) },
            decryptor = decryptor(),
            fileToken = fileToken,
        )

        private fun decryptor() = MtProtoCdnChunkDecryptor(key, iv, hashes)
    }

    suspend fun download(
        location: InputFileLocation_7d0b23428a,
        sink: MtProtoFileTransferSink,
        dcId: Int? = null,
        lengthLimit: Long? = null,
        startOverride: Long? = null,
    ) {
        var offset = startOverride ?: sink.committedOffset()
        val startOffset = offset
        var remaining = lengthLimit
        require(offset >= 0L) { "MTProto file transfer offset must not be negative" }
        val transport = dcId?.let { transportFactory.open(accountSlot, it) }
            ?: transportFactory.open(accountSlot)
        var cdn: CdnSession? = null
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val session = cdn
                if (session == null) {
                    val response = transport.execute(
                        GetFile(
                            precise = false,
                            cdnSupported = cdnTransportFactory != null,
                            location = location,
                            offset = offset,
                            limit = minOf(chunkSize, remaining?.toInt() ?: chunkSize),
                        ),
                    )
                    when (response) {
                        is File_34a32a2519 -> {
                            val bytes = response.bytes.toByteArray()
                            if (bytes.isEmpty()) break
                            sink.write(offset, bytes)
                            offset += bytes.size
                            remaining = remaining?.minus(bytes.size)
                            if (remaining != null && remaining!! <= 0L) break
                            if (bytes.size < chunkSize) break
                        }
                        is FileCdnRedirect -> {
                            val redirectFactory = cdnTransportFactory
                                ?: throw IllegalStateException("MTProto CDN transports are not configured")
                            cdn = CdnSession(redirectFactory.open(response.dcId), response)
                        }
                    }
                } else {
                    // Reads never straddle hash ranges so every chunk verifies exactly.
                    val range = session.hashRangeContaining(offset)
                    if (range == null) {
                        // All advertised hashes consumed: end of CDN-backed content.
                        if (offset != startOffset) break
                        throw IllegalStateException("MTProto CDN hash coverage gap at $offset")
                    }
                    val clampedLimit = minOf(chunkSize, (range.offset + range.limit - offset).toInt(), remaining?.toInt() ?: chunkSize)
                    val reader = session.refreshedReader()
                    when (val result = reader.read(offset, clampedLimit)) {
                        is MtProtoCdnReadResult.Chunk -> {
                            if (result.bytes.isEmpty()) break
                            sink.write(offset, result.bytes)
                            offset += result.bytes.size
                            remaining = remaining?.minus(result.bytes.size)
                            if (remaining != null && remaining!! <= 0L) break
                            if (result.bytes.size < chunkSize) break
                        }
                        is MtProtoCdnReadResult.ReuploadNeeded -> {
                            @Suppress("UNCHECKED_CAST")
                            val hashes = transport.execute(
                                ReuploadCdnFile(TlBytes.copyOf(session.fileToken), TlBytes.copyOf(result.requestToken)),
                            ) as List<FileHash_be7ffe4837>
                            session.replaceHashes(hashes)
                        }
                    }
                }
            }
            sink.complete(offset)
        } finally {
            try {
                cdn?.transport?.close()
            } finally {
                transport.close()
            }
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
