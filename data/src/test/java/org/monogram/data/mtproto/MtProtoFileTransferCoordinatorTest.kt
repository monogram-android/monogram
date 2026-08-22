package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.InputDocumentFileLocation
import org.monogram.mtproto.tl.generated.cloud.layer223.upload.FileCdnRedirect
import org.monogram.mtproto.tl.generated.cloud.layer223.upload.File_34a32a2519
import org.monogram.mtproto.tl.generated.cloud.layer223.upload.GetFile
import org.monogram.mtproto.tl.generated.cloud.layer223.storage.FileUnknown
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoFileTransferCoordinatorTest {
    @Test
    fun `resumes from committed offset and completes after partial chunk`() = runBlocking {
        val transport = RecordingTransport(listOf(ByteArray(1024) { 1 }, byteArrayOf(5, 6)))
        val sink = RecordingSink(committed = 8)
        val coordinator = MtProtoFileTransferCoordinator(
            transportFactory = MtProtoSessionTransportFactory { transport },
            chunkSize = 1024,
        )

        coordinator.download(location(), sink)

        assertEquals(listOf(8L, 1032L), transport.requests.map { it.offset })
        assertEquals(listOf(8L to ByteArray(1024) { 1 }.toList(), 1032L to listOf<Byte>(5, 6)), sink.writes)
        assertEquals(1034L, sink.completed)
        assertEquals(true, transport.closed)
    }

    @Test
    fun `opens document transfer at its persisted DC`() = runBlocking {
        val transport = RecordingTransport(listOf(byteArrayOf(5)))
        var requestedDc: Int? = null
        val coordinator = MtProtoFileTransferCoordinator(
            transportFactory = object : MtProtoSessionTransportFactory {
                override suspend fun open(accountSlot: String): MtProtoRpcTransport = error("home DC must not open")
                override suspend fun open(accountSlot: String, dcId: Int): MtProtoRpcTransport {
                    requestedDc = dcId
                    return transport
                }
            },
            chunkSize = 1024,
        )

        coordinator.download(location(), RecordingSink(), dcId = 4)

        assertEquals(4, requestedDc)
        assertEquals(true, transport.closed)
    }

    @Test
    fun `fails closed on CDN redirect without a CDN transport factory`() = runBlocking {
        val transport = RecordingTransport(emptyList(), redirect = true)
        val coordinator = MtProtoFileTransferCoordinator(
            transportFactory = MtProtoSessionTransportFactory { transport },
            chunkSize = 1024,
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { coordinator.download(location(), RecordingSink()) }
        }
        assertEquals(true, transport.closed)
    }

    @Test
    fun `follows CDN redirect decrypting and verifying chunks`() = runBlocking {
        val key = ByteArray(32) { (it + 1).toByte() }
        val iv = ByteArray(16) { (it + 3).toByte() }
        val offset = 64L
        val plain = ByteArray(2048) { (it % 253).toByte() }
        // The CDN encrypts every chunk independently: counter IV = the chunk's absolute offset.
        fun serverEncrypt(chunk: ByteArray, chunkOffset: Long): ByteArray {
            val chunkIv = iv.copyOf().also {
                it[12] = (chunkOffset.toInt() and 0xFF).toByte()
                it[13] = ((chunkOffset shr 8).toInt() and 0xFF).toByte()
                it[14] = ((chunkOffset shr 16).toInt() and 0xFF).toByte()
                it[15] = ((chunkOffset shr 24).toInt() and 0xFF).toByte()
            }
            val aes = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding")
            aes.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key.copyOf(), "AES"), javax.crypto.spec.IvParameterSpec(chunkIv))
            return aes.doFinal(chunk)
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        @Suppress("UNUSED_VARIABLE") val unusedStreamEncryption = null
        val hashes = listOf(0, 1024).map { piece ->
            org.monogram.mtproto.tl.generated.cloud.layer223.FileHash_be7ffe4837(
                offset + piece,
                1024,
                TlBytes.copyOf(digest.digest(plain.copyOfRange(piece, piece + 1024))),
            )
        }
        val redirect = FileCdnRedirect(
            dcId = 2,
            fileToken = TlBytes.copyOf(byteArrayOf(7, 7)),
            encryptionKey = TlBytes.copyOf(key),
            encryptionIv = TlBytes.copyOf(iv),
            fileHashes = hashes,
        )
        val mainTransport = RecordingTransport(emptyList(), fixedRedirect = redirect)
        var cdnDc: Int? = null
        val cdnTransport = object : MtProtoRpcTransport {
            var closed = false
            @Suppress("UNCHECKED_CAST")
            override suspend fun <R> execute(method: TlMethod<R>): R {
                val request = method as org.monogram.mtproto.tl.generated.cloud.layer223.upload.GetCdnFile
                val from = (request.offset - offset).toInt()
                if (from >= plain.size) {
                    return org.monogram.mtproto.tl.generated.cloud.layer223.upload.CdnFile_901d2b96cc(
                        TlBytes.copyOf(ByteArray(0)),
                    ) as R
                }
                val to = minOf(plain.size, from + request.limit)
                return org.monogram.mtproto.tl.generated.cloud.layer223.upload.CdnFile_901d2b96cc(
                    TlBytes.copyOf(serverEncrypt(plain.copyOfRange(from, to), request.offset)),
                ) as R
            }
            override fun close() { closed = true }
        }
        val sink = RecordingSink(committed = offset)
        val coordinator = MtProtoFileTransferCoordinator(
            transportFactory = MtProtoSessionTransportFactory { mainTransport },
            cdnTransportFactory = MtProtoCdnTransportFactory { dcId ->
                cdnDc = dcId
                cdnTransport
            },
            chunkSize = 1024,
        )

        coordinator.download(location(), sink)

        assertEquals(2, cdnDc)
        assertEquals(
            listOf(
                offset to plain.copyOfRange(0, 1024).toList(),
                (offset + 1024) to plain.copyOfRange(1024, 2048).toList(),
            ),
            sink.writes,
        )
        assertEquals(offset + plain.size, sink.completed)
        assertEquals(true, cdnTransport.closed)
        assertEquals(true, mainTransport.closed)
    }

    @Test
    fun `bounded range read stops exactly at the length limit`() = runBlocking {
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val transport = object : MtProtoRpcTransport {
            val requests = mutableListOf<GetFile>()
            var closed = false
            @Suppress("UNCHECKED_CAST")
            override suspend fun <R> execute(method: TlMethod<R>): R {
                val request = method as GetFile
                requests += request
                val from = request.offset.toInt()
                val to = minOf(payload.size, from + request.limit)
                return File_34a32a2519(FileUnknown, 0, TlBytes.copyOf(payload.copyOfRange(from, to))) as R
            }
            override fun close() { closed = true }
        }
        val sink = RecordingSink()
        val coordinator = MtProtoFileTransferCoordinator(
            transportFactory = MtProtoSessionTransportFactory { transport },
            chunkSize = 1024,
        )

        coordinator.download(location(), sink, lengthLimit = 1500L, startOverride = 2048L)

        // Requests are clamped to the remaining bound; the transfer stops at offset+limit.
        assertEquals(listOf(2048L, 3072L), transport.requests.map { it.offset })
        assertEquals(listOf(1024, 476), transport.requests.map { it.limit })
        assertEquals(
            listOf(
                2048L to payload.copyOfRange(2048, 3072).toList(),
                3072L to payload.copyOfRange(3072, 3548).toList(),
            ),
            sink.writes,
        )
        assertEquals(3548L, sink.completed)
        assertEquals(true, transport.closed)
    }

    private fun location() = InputDocumentFileLocation(
        id = 1L,
        accessHash = 2L,
        fileReference = TlBytes.copyOf(byteArrayOf(3)),
        thumbSize = "",
    )

    private class RecordingSink(private val committed: Long = 0L) : MtProtoFileTransferSink {
        val writes = mutableListOf<Pair<Long, List<Byte>>>()
        var completed: Long? = null
        override suspend fun committedOffset() = committed
        override suspend fun write(offset: Long, bytes: ByteArray) { writes += offset to bytes.toList() }
        override suspend fun complete(totalBytes: Long) { completed = totalBytes }
    }

    private class RecordingTransport(
        private val chunks: List<ByteArray>,
        private val redirect: Boolean = false,
        private val fixedRedirect: FileCdnRedirect? = null,
    ) : MtProtoRpcTransport {
        val requests = mutableListOf<GetFile>()
        var closed = false
        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R {
            fixedRedirect?.let { return it as R }
            val request = method as GetFile
            requests += request
            return if (redirect) {
                FileCdnRedirect(1, TlBytes.copyOf(byteArrayOf()), TlBytes.copyOf(byteArrayOf()), TlBytes.copyOf(byteArrayOf()), emptyList()) as R
            } else {
                File_34a32a2519(FileUnknown, 0, TlBytes.copyOf(chunks[requests.lastIndex])) as R
            }
        }
        override fun close() { closed = true }
    }
}
