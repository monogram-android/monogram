package org.monogram.mtproto.codec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.tl.runtime.TlCodec
import org.monogram.mtproto.tl.runtime.TlDecodeContext
import org.monogram.mtproto.tl.runtime.TlLimitExceededException
import org.monogram.mtproto.tl.runtime.TlLimitKind
import org.monogram.mtproto.tl.runtime.TlLimits
import org.monogram.mtproto.tl.runtime.TlSchemaIdentity
import org.monogram.mtproto.tl.runtime.TlSchemaKind
import org.monogram.mtproto.tl.generated.transport.DestroyAuthKeyNone
import org.monogram.mtproto.tl.generated.transport.registry.TransportConstructorRegistry

class TlBinaryCodecTest {
    private val context = TlDecodeContext(TlSchemaIdentity(TlSchemaKind.TRANSPORT, null), 0, TlLimits.DEFAULT)

    @Test
    fun `primitive bool bytes string fixed values and padding round trip`() {
        val writer = TlBinaryWriter()
        writer.writeInt(0x12345678)
        writer.writeLong(0x0102030405060708L)
        writer.writeDouble(3.25)
        writer.writeBool(true)
        writer.writeBool(false)
        writer.writeBytes(org.monogram.mtproto.tl.runtime.TlBytes.copyOf(byteArrayOf(1, 2, 3)))
        writer.writeString("тест")
        val int128 = org.monogram.mtproto.tl.runtime.TlInt128.copyOf(ByteArray(16) { it.toByte() })
        val int256 = org.monogram.mtproto.tl.runtime.TlInt256.copyOf(ByteArray(32) { (it * 2).toByte() })
        writer.writeInt128(int128)
        writer.writeInt256(int256)

        val reader = TlBinaryReader(writer.toByteArray())
        assertEquals(0x12345678, reader.readInt())
        assertEquals(0x0102030405060708L, reader.readLong())
        assertEquals(3.25, reader.readDouble(), 0.0)
        assertEquals(true, reader.readBool(context))
        assertEquals(false, reader.readBool(context))
        assertArrayEquals(byteArrayOf(1, 2, 3), reader.readBytes(context).toByteArray())
        assertEquals("тест", reader.readString(context))
        assertEquals(int128, reader.readInt128())
        assertEquals(int256, reader.readInt256())
        assertEquals(0, reader.remaining)
    }

    @Test
    fun `bytes length prefix boundary and canonical zero padding`() {
        listOf(0, 1, 3, 252, 253, 254, 255, 16_383).forEach { length ->
            val value = ByteArray(length) { (it and 0x7f).toByte() }
            val writer = TlBinaryWriter(TlLimits.DEFAULT.lowered(maxObjectBytes = 16 * 1024 * 1024))
            writer.writeBytes(org.monogram.mtproto.tl.runtime.TlBytes.copyOf(value))
            val encoded = writer.toByteArray()
            val expectedPrefix = if (length < 254) 1 else 4
            assertEquals(0, encoded.size % 4)
            assertEquals(expectedPrefix, if (length < 254) 1 else 4)
            assertArrayEquals(value, TlBinaryReader(encoded).readBytes(context).toByteArray())
        }
    }

    @Test
    fun `vector round trip writes constructor and enforces count`() {
        val codec = object : TlCodec<Int> {
            override fun read(reader: org.monogram.mtproto.tl.runtime.TlReader, context: TlDecodeContext): Int = reader.readInt()
            override fun write(writer: org.monogram.mtproto.tl.runtime.TlWriter, value: Int) = writer.writeInt(value)
        }
        val writer = TlBinaryWriter()
        writer.writeVector(listOf(7, 8, 9), codec)
        val reader = TlBinaryReader(writer.toByteArray())
        assertEquals(listOf(7, 8, 9), reader.readVector(codec, context))
        assertEquals(0, reader.remaining)

        val limited = TlBinaryReader(byteArrayOf(0x15, 0xc4.toByte(), 0xb5.toByte(), 0x1c, 2, 0, 0, 0), limits = TlLimits.DEFAULT.lowered(maxVectorElements = 1))
        val failure = assertThrows(TlLimitExceededException::class.java) { limited.readVector(codec, context) }
        assertEquals(TlLimitKind.VECTOR_ELEMENTS, failure.limitKind)
    }

    @Test
    fun `malformed utf8 padding bool vector and deferred data are rejected`() {
        val badUtf8 = byteArrayOf(1, 0xc3.toByte(), 0, 0)
        assertThrows(Exception::class.java) { TlBinaryReader(badUtf8).readString(context) }

        val badPadding = byteArrayOf(1, 42, 1, 0)
        assertThrows(IllegalArgumentException::class.java) { TlBinaryReader(badPadding).readBytes(context) }

        val badBool = byteArrayOf(1, 2, 3, 4)
        assertThrows(IllegalArgumentException::class.java) { TlBinaryReader(badBool).readBool(context) }

        val badVector = byteArrayOf(1, 2, 3, 4, 0, 0, 0, 0)
        assertThrows(IllegalArgumentException::class.java) { TlBinaryReader(badVector).readVector(IntCodec, context) }

        val reader = TlBinaryReader(byteArrayOf(1, 2, 3, 4))
        assertArrayEquals(byteArrayOf(1, 2), reader.readDeferredObject(2, context).toByteArray())
        assertArrayEquals(byteArrayOf(3, 4), reader.readRemainingDeferredObject(context).toByteArray())
        assertThrows(IllegalArgumentException::class.java) { TlBinaryReader(byteArrayOf(1)).readDeferredObject(2, context) }
    }

    @Test
    fun `reader and writer enforce lowered object bounds`() {
        val limits = TlLimits.DEFAULT.lowered(maxObjectBytes = 4)
        assertThrows(TlLimitExceededException::class.java) { TlBinaryReader(ByteArray(5), limits = limits) }
        val writer = TlBinaryWriter(limits)
        writer.writeInt(1)
        assertThrows(IllegalArgumentException::class.java) { writer.writeInt(2) }
    }

    @Test
    fun `generated transport registry round trips through real binary reader and writer`() {
        val writer = TlBinaryWriter()
        TransportConstructorRegistry.encode(writer, DestroyAuthKeyNone)
        val encoded = writer.toByteArray()
        assertEquals(4, encoded.size)

        val reader = TlBinaryReader(encoded)
        val id = reader.readInt().toUInt()
        val decoded = TransportConstructorRegistry.decode(id, reader, context)
        assertEquals(DestroyAuthKeyNone, decoded)
        assertEquals(0, reader.remaining)
    }

    private object IntCodec : TlCodec<Int> {
        override fun read(reader: org.monogram.mtproto.tl.runtime.TlReader, context: TlDecodeContext): Int = reader.readInt()
        override fun write(writer: org.monogram.mtproto.tl.runtime.TlWriter, value: Int) = writer.writeInt(value)
    }
}
