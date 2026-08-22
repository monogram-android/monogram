package org.monogram.mtproto.upload

import org.monogram.mtproto.tl.generated.cloud.layer223.upload.CdnFileReuploadNeeded
import org.monogram.mtproto.tl.generated.cloud.layer223.upload.CdnFile_901d2b96cc
import org.monogram.mtproto.tl.generated.cloud.layer223.upload.GetCdnFile
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.tl.runtime.TlObject

sealed interface MtProtoCdnReadResult {
    /** Decrypted and hash-verified bytes starting exactly at the requested offset. */
    data class Chunk(val offset: Long, val bytes: ByteArray) : MtProtoCdnReadResult

    /** The CDN DC lost this file; the main DC must reupload it via `upload.reuploadCdnFile`. */
    data class ReuploadNeeded(val requestToken: ByteArray) : MtProtoCdnReadResult
}

fun interface MtProtoCdnExecutor {
    suspend fun execute(method: TlMethod<*>): TlObject
}

/** Reads, decrypts, and verifies CDN file chunks from one `fileCdnRedirect` session. */
class MtProtoCdnFileReader(
    private val cdnExecutor: MtProtoCdnExecutor,
    private val decryptor: MtProtoCdnChunkDecryptor,
    private val fileToken: ByteArray,
) {
    suspend fun read(offset: Long, limit: Int): MtProtoCdnReadResult {
        require(offset >= 0) { "CDN read offset must not be negative" }
        require(limit > 0) { "CDN read limit must be positive" }
        return when (val result = cdnExecutor.execute(GetCdnFile(
            fileToken = org.monogram.mtproto.tl.runtime.TlBytes.copyOf(fileToken),
            offset = offset,
            limit = limit,
        ))) {
            is CdnFile_901d2b96cc -> {
                val plain = decryptor.decrypt(offset, result.bytes.toByteArray())
                MtProtoCdnReadResult.Chunk(offset, plain)
            }
            is CdnFileReuploadNeeded ->
                MtProtoCdnReadResult.ReuploadNeeded(result.requestToken.toByteArray())
            else ->
                throw IllegalStateException("Unsupported upload.cdnFile constructor ${result.constructorId}")
        }
    }
}
