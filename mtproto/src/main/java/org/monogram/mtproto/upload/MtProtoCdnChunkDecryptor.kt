package org.monogram.mtproto.upload

import java.security.MessageDigest
import org.monogram.mtproto.crypto.AesCtr
import org.monogram.mtproto.tl.generated.cloud.layer223.FileHash_be7ffe4837

/** Decrypts and verifies one CDN file chunk against the redirect's file-hash ranges. */
class MtProtoCdnChunkDecryptor(
    private val encryptionKey: ByteArray,
    private val encryptionIv: ByteArray,
    private val fileHashes: List<FileHash_be7ffe4837>,
) {
    init {
        require(encryptionKey.size == KEY_BYTES) { "CDN encryption key must be 32 bytes" }
        require(encryptionIv.size == IV_BYTES) { "CDN encryption iv must be 16 bytes" }
    }

    /**
     * Decrypts [cipherBytes] starting at absolute file [offset] and verifies every covered
     * hash range. Throws when a range is missing, only partially covered without full coverage,
     * or when any SHA-256 segment mismatches.
     */
    fun decrypt(offset: Long, cipherBytes: ByteArray): ByteArray {
        val plain = AesCtr.decrypt(cipherBytes, encryptionKey, ivFor(offset))
        verify(plain, offset)
        return plain
    }

    /** Finds the hash range containing [offset], if the redirect advertised it. */
    fun hashRangeContaining(offset: Long): FileHash_be7ffe4837? =
        fileHashes.firstOrNull { offset >= it.offset && offset < it.offset + it.limit }

    private fun verify(plain: ByteArray, startOffset: Long) {
        if (plain.isEmpty()) return
        val endOffset = startOffset + plain.size
        val digest = MessageDigest.getInstance("SHA-256")
        var cursor = startOffset
        while (cursor < endOffset) {
            val range = hashRangeContaining(cursor)
                ?: throw IllegalStateException("CDN hash coverage gap at offset $cursor")
            val rangeEnd = range.offset + range.limit
            val segmentEnd = minOf(endOffset, rangeEnd)
            val slice = plain.copyOfRange((cursor - startOffset).toInt(), (segmentEnd - startOffset).toInt())
            val actual = digest.digest(slice)
            val expected = range.hash.toByteArray()
            if (!actual.contentEquals(expected)) {
                throw IllegalStateException("CDN hash mismatch in range ${range.offset}..${range.offset + range.limit}")
            }
            cursor = segmentEnd
        }
    }

    private fun ivFor(offset: Long): ByteArray {
        val iv = encryptionIv.copyOf()
        // TDLib FileDownloader: as<uint32>(&iv[12]) = offset (little-endian low 32 bits).
        iv[12] = (offset.toInt() and 0xFF).toByte()
        iv[13] = ((offset.toInt() shr 8) and 0xFF).toByte()
        iv[14] = ((offset.toInt() shr 16) and 0xFF).toByte()
        iv[15] = ((offset.toInt() shr 24) and 0xFF).toByte()
        return iv
    }

    private companion object {
        const val KEY_BYTES = 32
        const val IV_BYTES = 16
    }
}
