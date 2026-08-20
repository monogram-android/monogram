package org.monogram.data.mtproto

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.InputFile_ef0db4e0fa
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
