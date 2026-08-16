package org.monogram.mtproto.transport

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.codec.TlBinaryWriter
import org.monogram.mtproto.tl.generated.transport.ReqPqMulti
import org.monogram.mtproto.tl.generated.transport.ResPq_0c012ada9f
import org.monogram.mtproto.tl.generated.transport.registry.TransportConstructorRegistry
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.tl.runtime.TlDecodeContext
import org.monogram.mtproto.tl.runtime.TlInt128
import org.monogram.mtproto.tl.runtime.TlLimits
import org.monogram.mtproto.tl.runtime.TlSchemaIdentity
import org.monogram.mtproto.tl.runtime.TlSchemaKind

class UnencryptedMessageCodecTest {
    @Test
    fun messageIdsAreAlignedMonotonicAndTimeBased() {
        val generator = ClientMessageIdGenerator { 1_700_000_000_123L }
        val first = generator.next()
        val second = generator.next()
        assertEquals(0L, first % 4)
        assertEquals(first + 4, second)
        assertEquals(1_700_000_000L, first ushr 32)
    }

    @Test
    fun encodesMethodEnvelopeAndDecodesGeneratedResult() {
        val nonce = TlInt128.copyOf(ByteArray(16) { it.toByte() })
        val method = ReqPqMulti(nonce)
        val request = UnencryptedMessageCodec.encodeMethod(method, 0x0102030405060708L)
        val requestBuffer = ByteBuffer.wrap(request).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0L, requestBuffer.long)
        assertEquals(0x0102030405060708L, requestBuffer.long)
        assertEquals(20, requestBuffer.int)
        assertEquals(ReqPqMulti.CONSTRUCTOR_ID.toInt(), requestBuffer.int)

        val response = ResPq_0c012ada9f(
            nonce,
            TlInt128.copyOf(ByteArray(16) { (it + 16).toByte() }),
            TlBytes.copyOf(byteArrayOf(15)),
            listOf(42L),
        )
        val writer = TlBinaryWriter()
        TransportConstructorRegistry.encode(writer, response)
        val responseEnvelope = serverEnvelope(0x0102030405060709L, writer.toByteArray())
        assertEquals(response, UnencryptedMessageCodec.decodeResult(method, responseEnvelope, CONTEXT))
    }

    @Test
    fun rejectsInvalidAuthKeyIdsMessageIdsAndLengths() {
        val valid = serverEnvelope(5L, byteArrayOf(1, 2, 3, 4))
        assertThrows(IllegalArgumentException::class.java) {
            UnencryptedMessageCodec.decode(valid.copyOf().also { it[0] = 1 })
        }
        assertThrows(IllegalArgumentException::class.java) {
            UnencryptedMessageCodec.decode(valid.copyOf().also { it[8] = 4 })
        }
        assertThrows(IllegalArgumentException::class.java) {
            UnencryptedMessageCodec.decode(valid.copyOf(valid.size - 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UnencryptedMessageCodec.encode(4L, ByteArray(5))
        }
    }

    private fun serverEnvelope(messageId: Long, body: ByteArray): ByteArray =
        ByteBuffer.allocate(20 + body.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(0L)
            putLong(messageId)
            putInt(body.size)
            put(body)
        }.array()

    companion object {
        private val CONTEXT = TlDecodeContext(
            TlSchemaIdentity(TlSchemaKind.TRANSPORT, null),
            0,
            TlLimits.DEFAULT,
        )
    }
}
