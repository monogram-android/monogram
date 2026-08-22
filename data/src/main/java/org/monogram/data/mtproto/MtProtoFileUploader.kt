package org.monogram.data.mtproto

import java.io.File
import java.security.MessageDigest
import org.monogram.mtproto.tl.generated.cloud.layer223.InputFileBig
import org.monogram.mtproto.tl.generated.cloud.layer223.InputFile_a29444d0dd
import org.monogram.mtproto.tl.generated.cloud.layer223.InputFile_ef0db4e0fa
import org.monogram.mtproto.tl.generated.cloud.layer223.upload.SaveBigFilePart
import org.monogram.mtproto.tl.generated.cloud.layer223.upload.SaveFilePart
import org.monogram.mtproto.tl.runtime.TlBytes

/** Durable per-file upload progress so interrupted uploads resume instead of restarting. */
internal interface MtProtoUploadProgressStore {
    suspend fun committedBytes(fileKey: String): Long
    suspend fun advance(fileKey: String, path: String, expectedSize: Long, committedBytes: Long)
    suspend fun clear(fileKey: String)
}

internal fun interface MtProtoFileUploader {
    suspend fun upload(path: String): InputFile_a29444d0dd
}

/**
 * Uploads files through `upload.saveFilePart` (small) or `upload.saveBigFilePart` (large).
 *
 * Uploads are crash-resumable: the file id is derived deterministically from the source path and
 * size, and durable progress records skip already-confirmed parts after a restart.
 */
internal class TelegramMtProtoFileUploader(
    private val transportFactory: MtProtoSessionTransportFactory,
    private val progressStore: MtProtoUploadProgressStore? = null,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoFileUploader {
    override suspend fun upload(path: String): InputFile_a29444d0dd {
        val file = File(path)
        require(file.isFile) { "Upload source is not a regular file" }
        val size = file.length()
        require(size > 0) { "Upload source is empty" }
        require(size <= MAX_UPLOAD_BYTES) { "MTProto upload exceeds supported size" }
        val isBig = size > SMALL_MAX_BYTES
        val fileKey = uploadFileKey(path, size)
        val fileId = deterministicFileId(path, size)

        var committed = progressStore?.committedBytes(fileKey) ?: 0L
        if (committed % PART_SIZE != 0L || committed > size) {
            // A partially written record cannot be trusted for part alignment; restart cleanly.
            progressStore?.clear(fileKey)
            committed = 0L
        }

        val md5 = if (!isBig) file.md5() else null
        var part = (committed / PART_SIZE).toInt()
        transportFactory.open(accountSlot).use { transport ->
            file.inputStream().use { input ->
                if (committed > 0L) input.skipNBytes(committed)
                val buffer = ByteArray(PART_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(count > 0) { "MTProto upload produced an empty part" }
                    val bytes = TlBytes.copyOf(buffer.copyOf(count))
                    val fileTotalParts = ((size + PART_SIZE - 1) / PART_SIZE).toInt()
                    val accepted = if (isBig) {
                        transport.execute(SaveBigFilePart(fileId, part, fileTotalParts, bytes)) as Boolean
                    } else {
                        transport.execute(SaveFilePart(fileId, part, bytes)) as Boolean
                    }
                    check(accepted) {
                        "${if (isBig) "upload.saveBigFilePart" else "upload.saveFilePart"} rejected part $part"
                    }
                    committed += count
                    part++
                    progressStore?.advance(fileKey, path, size, committed)
                }
            }
        }
        progressStore?.clear(fileKey)
        val parts = ((size + PART_SIZE - 1) / PART_SIZE).toInt()
        return if (isBig) {
            InputFileBig(fileId, parts, file.name)
        } else {
            InputFile_ef0db4e0fa(fileId, parts, file.name, requireNotNull(md5))
        }
    }

    private fun File.md5(): String {
        val digest = MessageDigest.getInstance("MD5")
        inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
        const val PART_SIZE = 512 * 1024
        const val COPY_BUFFER = 128 * 1024
        const val SMALL_MAX_BYTES = 10L * 1024L * 1024L
        const val MAX_UPLOAD_BYTES = 2000L * 1024L * 1024L

        /** Stable across processes so a restarted upload reuses the server-side partial upload. */
        fun deterministicFileId(path: String, size: Long): Long {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$path:$size".toByteArray())
            var id = 0L
            for (byte in digest.copyOf(8)) id = (id shl 8) or (byte.toLong() and 0xFF)
            return id and Long.MAX_VALUE
        }

        fun uploadFileKey(path: String, size: Long): String =
            "upload:" + MessageDigest.getInstance("SHA-256")
                .digest("$path:$size".toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(32)
    }
}
