package org.monogram.mtproto.upload

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.FileHash_be7ffe4837
import org.monogram.mtproto.tl.generated.cloud.layer223.upload.CdnFileReuploadNeeded
import org.monogram.mtproto.tl.generated.cloud.layer223.upload.CdnFile_901d2b96cc
import org.monogram.mtproto.tl.generated.cloud.layer223.upload.GetCdnFile
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.tl.runtime.TlObject

class MtProtoCdnFileReaderTest {
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val iv = ByteArray(16) { (it + 3).toByte() }
    private val plain = ByteArray(8192) { (it % 251).toByte() }

    private fun hashFor(offset: Long, limit: Int, data: ByteArray, dataStart: Long): FileHash_be7ffe4837 {
        val from = (offset - dataStart).toInt()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return FileHash_be7ffe4837(offset, limit, TlBytes.copyOf(digest.digest(data.copyOfRange(from, from + limit))))
    }

    @Test
    fun `decrypts chunk with offset-derived counter iv and verifies hashes`() {
        // Encrypt at chunk offset 4096 the way the CDN server would.
        val serverIv = iv.copyOf().also {
            it[12] = ((4096) and 0xFF).toByte()
            it[13] = ((4096 shr 8) and 0xFF).toByte()
            it[14] = ((4096 shr 16) and 0xFF).toByte()
            it[15] = ((4096 shr 24) and 0xFF).toByte()
        }
        val chunkLength = 1024
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.copyOf(), "AES"), IvParameterSpec(serverIv))
        val encrypted = cipher.doFinal(plain.copyOfRange(4096, 4096 + chunkLength))
        val decryptor = MtProtoCdnChunkDecryptor(
            encryptionKey = key,
            encryptionIv = iv,
            fileHashes = listOf(hashFor(4096L, chunkLength, plain, dataStart = 0)),
        )

        assertTrue(decryptor.hashRangeContaining(5000L) != null)
        assertEquals(plain.copyOfRange(4096, 4096 + chunkLength).toList(), decryptor.decrypt(4096L, encrypted).toList())
    }

    @Test
    fun `rejects ciphertext whose plaintext fails hash verification`() {
        val corrupt = plain.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val serverIv = iv.copyOf()
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.copyOf(), "AES"), IvParameterSpec(serverIv))
        val encrypted = cipher.doFinal(corrupt)
        val decryptor = MtProtoCdnChunkDecryptor(key, iv, listOf(hashFor(0L, plain.size, plain, 0)))

        assertThrows(IllegalStateException::class.java) { decryptor.decrypt(0L, encrypted) }
    }

    @Test
    fun `fails closed when a covered range has no advertised hash`() {
        val decryptor = MtProtoCdnChunkDecryptor(key, iv, emptyList())
        val serverIv = iv.copyOf()
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.copyOf(), "AES"), IvParameterSpec(serverIv))

        assertThrows(IllegalStateException::class.java) { decryptor.decrypt(0L, cipher.doFinal(ByteArray(16))) }
    }

    @Test
    fun `reader returns decrypted chunks and surfaces reupload tokens`() = runBlocking {
        var calls = 0
        val responses = ArrayDeque<TlObject>().apply {
            add(CdnFileReuploadNeeded(TlBytes.copyOf(byteArrayOf(1, 2, 3))))
            add(CdnFile_901d2b96cc(TlBytes.copyOf(ByteArray(0))))
        }
        val requests = mutableListOf<GetCdnFile>()
        val reader = MtProtoCdnFileReader(
            cdnExecutor = { method ->
                calls++
                requests += method as GetCdnFile
                responses.removeFirst()
            },
            decryptor = MtProtoCdnChunkDecryptor(key, iv, listOf(hashFor(0L, plain.size, plain, 0))),
            fileToken = byteArrayOf(9, 8, 7),
        )

        val reupload = reader.read(offset = 0, limit = 512)
        assertTrue(reupload is MtProtoCdnReadResult.ReuploadNeeded)
        assertEquals(listOf(1, 2, 3), (reupload as MtProtoCdnReadResult.ReuploadNeeded).requestToken.map { it.toInt() })

        val chunk = reader.read(offset = 0, limit = 256)
        assertTrue(chunk is MtProtoCdnReadResult.Chunk)

        assertEquals(2, calls)
        assertEquals(0L, requests[0].offset)
        assertEquals(512, requests[0].limit)
        assertEquals(listOf(9, 8, 7), requests[0].fileToken.toByteArray().map { it.toInt() })
    }

    @Suppress("UNUSED_PARAMETER")
    private fun unused(x: Any?) = Unit
}
