package org.monogram.mtproto.transport

import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.codec.TlBinaryWriter
import org.monogram.mtproto.tl.generated.transport.ReqPqMulti
import org.monogram.mtproto.tl.generated.transport.ResPq_0c012ada9f
import org.monogram.mtproto.tl.generated.transport.registry.TransportConstructorRegistry
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.tl.runtime.TlInt128

class IntermediateTcpHandshakeTransportTest {
    @Test
    fun exchangesGeneratedMethodOverRealIntermediateTcpStream() {
        ServerSocket(0).use { server ->
            val serverFailure = AtomicReference<Throwable?>()
            val worker = thread(name = "mtproto-loopback-server") {
                try {
                    server.accept().use { peer ->
                        val input = peer.getInputStream()
                        assertArrayEquals(IntermediateTransportFraming.preamble, readFully(input, 4))
                        val header = readFully(input, 4)
                        val frameBytes = IntermediateTransportFraming.expectedFrameBytes(header)
                        val request = readFully(input, frameBytes - 4)
                        val envelope = ByteBuffer.wrap(request).order(ByteOrder.LITTLE_ENDIAN)
                        assertEquals(0L, envelope.long)
                        envelope.long
                        val bodySize = envelope.int
                        assertEquals(bodySize, envelope.remaining())
                        assertEquals(ReqPqMulti.CONSTRUCTOR_ID.toInt(), envelope.int)
                        val nonceBytes = ByteArray(16).also(envelope::get)
                        val response = ResPq_0c012ada9f(
                            TlInt128.copyOf(nonceBytes),
                            TlInt128.copyOf(ByteArray(16) { (it + 16).toByte() }),
                            TlBytes.copyOf(byteArrayOf(15)),
                            listOf(42L),
                        )
                        val writer = TlBinaryWriter()
                        TransportConstructorRegistry.encode(writer, response)
                        val responseBody = writer.toByteArray()
                        val responseEnvelope = serverEnvelope(5L, responseBody)
                        val responseFrame = IntermediateTransportFraming.encode(responseEnvelope)
                        peer.getOutputStream().apply { write(responseFrame); flush() }
                    }
                } catch (failure: Throwable) {
                    serverFailure.set(failure)
                }
            }

            val nonce = TlInt128.copyOf(ByteArray(16) { it.toByte() })
            val transport = IntermediateTcpHandshakeTransport("127.0.0.1", server.localPort)
            val result = runBlocking { transport.execute(ReqPqMulti(nonce)) } as ResPq_0c012ada9f
            assertEquals(nonce, result.nonce)
            transport.close()
            worker.join(5_000)
            serverFailure.get()?.let { throw AssertionError("Loopback server failed", it) }
            assertThrows(IllegalStateException::class.java) {
                runBlocking { transport.execute(ReqPqMulti(nonce)) }
            }
        }
    }

    private fun serverEnvelope(messageId: Long, body: ByteArray): ByteArray =
        ByteBuffer.allocate(20 + body.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(0L)
            putLong(messageId)
            putInt(body.size)
            put(body)
        }.array()

    private fun readFully(input: java.io.InputStream, count: Int): ByteArray = ByteArray(count).also { bytes ->
        var offset = 0
        while (offset < count) {
            val read = input.read(bytes, offset, count - offset)
            check(read >= 0)
            offset += read
        }
    }
}
