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
    fun `fails closed on CDN redirect and closes transport`() = runBlocking {
        val transport = RecordingTransport(emptyList(), redirect = true)
        val coordinator = MtProtoFileTransferCoordinator(
            transportFactory = MtProtoSessionTransportFactory { transport },
            chunkSize = 1024,
        )

        assertThrows(UnsupportedOperationException::class.java) {
            runBlocking { coordinator.download(location(), RecordingSink()) }
        }
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
    ) : MtProtoRpcTransport {
        val requests = mutableListOf<GetFile>()
        var closed = false
        override suspend fun <R> execute(method: TlMethod<R>): R {
            val request = method as GetFile
            requests += request
            @Suppress("UNCHECKED_CAST")
            return if (redirect) {
                FileCdnRedirect(1, TlBytes.copyOf(byteArrayOf()), TlBytes.copyOf(byteArrayOf()), TlBytes.copyOf(byteArrayOf()), emptyList()) as R
            } else {
                File_34a32a2519(FileUnknown, 0, TlBytes.copyOf(chunks[requests.lastIndex])) as R
            }
        }
        override fun close() { closed = true }
    }
}
