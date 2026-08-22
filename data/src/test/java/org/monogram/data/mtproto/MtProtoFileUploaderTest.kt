package org.monogram.data.mtproto

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.InputFileBig
import org.monogram.mtproto.tl.generated.cloud.layer223.InputFile_ef0db4e0fa
import org.monogram.mtproto.tl.generated.cloud.layer223.upload.SaveBigFilePart
import org.monogram.mtproto.tl.generated.cloud.layer223.upload.SaveFilePart
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoFileUploaderTest {
    @Test
    fun `uploads regular file as ordered parts with md5`() = runBlocking {
        val source = File.createTempFile("mtproto-upload", ".bin")
        source.writeBytes(ByteArray(512 * 1024 + 3) { it.toByte() })
        try {
            val transport = RecordingTransport()
            val uploader = TelegramMtProtoFileUploader(MtProtoSessionTransportFactory { transport })

            val uploaded = uploader.upload(source.path) as InputFile_ef0db4e0fa

            assertEquals(2, uploaded.parts)
            assertEquals(source.name, uploaded.name)
            assertEquals(listOf(0, 1), transport.parts.map(SaveFilePart::filePart))
            assertEquals(listOf(512 * 1024, 3), transport.parts.map { it.bytes.toByteArray().size })
            assertEquals(1, transport.closeCalls)
            assertTrue(uploaded.md5Checksum.matches(Regex("[0-9a-f]{32}")))
        } finally {
            source.delete()
        }
    }

    @Test
    fun `resumes from durable progress and clears it on completion`() = runBlocking {
        val source = File.createTempFile("mtproto-resume", ".bin")
        val part = 512 * 1024
        source.writeBytes(ByteArray(2 * part) { it.toByte() })
        try {
            val transport = RecordingTransport()
            var committed: Long = part.toLong()
            var cleared = false
            val store = object : MtProtoUploadProgressStore {
                override suspend fun committedBytes(fileKey: String) = committed
                override suspend fun advance(fileKey: String, path: String, expectedSize: Long, committedBytes: Long) {
                    committed = committedBytes
                }
                override suspend fun clear(fileKey: String) { cleared = true }
            }
            val uploader = TelegramMtProtoFileUploader(
                MtProtoSessionTransportFactory { transport },
                progressStore = store,
            )

            val uploaded = uploader.upload(source.path) as InputFile_ef0db4e0fa

            // Only the second part is re-sent after the crash.
            assertEquals(listOf(1), transport.parts.map(SaveFilePart::filePart))
            assertEquals(part, transport.parts.single().bytes.toByteArray().size)
            assertEquals(2, uploaded.parts)
            assertTrue(cleared)
        } finally {
            source.delete()
        }
    }

    @Test
    fun `restarts cleanly when the durable offset is not part aligned`() = runBlocking {
        val source = File.createTempFile("mtproto-misaligned", ".bin")
        val part = 512 * 1024
        source.writeBytes(ByteArray(part + 7) { it.toByte() })
        try {
            val transport = RecordingTransport()
            val store = object : MtProtoUploadProgressStore {
                override suspend fun committedBytes(fileKey: String) = 100L
                override suspend fun advance(fileKey: String, path: String, expectedSize: Long, committedBytes: Long) = Unit
                override suspend fun clear(fileKey: String) = Unit
            }
            val uploader = TelegramMtProtoFileUploader(
                MtProtoSessionTransportFactory { transport },
                progressStore = store,
            )

            uploader.upload(source.path)

            assertEquals(listOf(0, 1), transport.parts.map(SaveFilePart::filePart))
        } finally {
            source.delete()
        }
    }

    @Test
    fun `uploads large files through saveBigFilePart`() = runBlocking {
        // Simulate a big file without allocating 10MB+ by using a length-only fake? The uploader
        // reads real bytes; use a sparse temp file of just over the small threshold.
        val source = File.createTempFile("mtproto-big", ".bin")
        source.writeBytes(ByteArray(10 * 1024 * 1024 + 512 * 1024))
        try {
            val transport = BigRecordingTransport()
            val uploader = TelegramMtProtoFileUploader(MtProtoSessionTransportFactory { transport })

            val uploaded = uploader.upload(source.path) as InputFileBig

            assertEquals(21, uploaded.parts)
            assertTrue(transport.bigParts.isNotEmpty())
            assertEquals(listOf(0, 20), listOf(transport.bigParts.first().filePart, transport.bigParts.last().filePart))
            assertTrue(transport.bigParts.all { it.fileTotalParts == 21 })
        } finally {
            source.delete()
        }
    }

    private class BigRecordingTransport : MtProtoRpcTransport {
        val bigParts = mutableListOf<SaveBigFilePart>()
        override suspend fun <R> execute(method: TlMethod<R>): R {
            bigParts += method as SaveBigFilePart
            return true as R
        }
        override fun close() = Unit
    }

    private class RecordingTransport : MtProtoRpcTransport {
        val parts = mutableListOf<SaveFilePart>()
        var closeCalls = 0

        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R {
            parts += method as SaveFilePart
            return true as R
        }

        override fun close() {
            closeCalls++
        }
    }
}
