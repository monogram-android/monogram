package org.monogram.mtproto.codec

import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.tl.generated.transport.DestroyAuthKeyNone
import org.monogram.mtproto.tl.generated.transport.DestroyAuthKeyNoneCodec
import org.monogram.mtproto.tl.generated.transport.registry.TransportConstructorRegistry
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.tl.runtime.TlDecodeContext
import org.monogram.mtproto.tl.runtime.TlLimitExceededException
import org.monogram.mtproto.tl.runtime.TlLimitKind
import org.monogram.mtproto.tl.runtime.TlLimits
import org.monogram.mtproto.tl.runtime.TlSchemaIdentity
import org.monogram.mtproto.tl.runtime.TlSchemaKind
import org.monogram.mtproto.tl.runtime.TlSchemaMismatchException

class TlBinaryRuntimePropertyTest {
    private val transport = TlSchemaIdentity(TlSchemaKind.TRANSPORT, null)
    private val context = TlDecodeContext(transport, 0, TlLimits.DEFAULT)

    @Test
    fun `bounded random primitive and bytes round trips are deterministic`() {
        val random = Random(0x544c2026L)
        repeat(500) {
            val intValue = random.nextInt()
            val longValue = random.nextLong()
            val doubleValue = Double.fromBits(random.nextLong())
            val bytes = ByteArray(random.nextInt(2049)).also(random::nextBytes)
            val writer = TlBinaryWriter()
            writer.writeInt(intValue)
            writer.writeLong(longValue)
            writer.writeDouble(doubleValue)
            writer.writeBytes(TlBytes.copyOf(bytes))
            val encoded = writer.toByteArray()

            val reader = TlBinaryReader(encoded)
            assertEquals(intValue, reader.readInt())
            assertEquals(longValue, reader.readLong())
            assertEquals(doubleValue.toBits(), reader.readDouble().toBits())
            assertArrayEquals(bytes, reader.readBytes(context).toByteArray())
            assertEquals(0, reader.remaining)
        }
    }

    @Test
    fun `top level helpers require exact consumption and selected schema`() {
        val encoded = TlBinaryCodec.encode(DestroyAuthKeyNoneCodec, DestroyAuthKeyNone)
        assertEquals(DestroyAuthKeyNone, TlBinaryCodec.decode(DestroyAuthKeyNoneCodec, encoded, context))
        assertEquals(DestroyAuthKeyNone, TlBinaryCodec.decodeObject(TransportConstructorRegistry, encoded, context))
        assertThrows(IllegalArgumentException::class.java) {
            TlBinaryCodec.decode(DestroyAuthKeyNoneCodec, encoded + byteArrayOf(0), context)
        }

        val cloudContext = TlDecodeContext(TlSchemaIdentity(TlSchemaKind.CLOUD, 223), 0, TlLimits.DEFAULT)
        val mismatch = assertThrows(TlSchemaMismatchException::class.java) {
            TlBinaryCodec.decodeObject(TransportConstructorRegistry, encoded, cloudContext)
        }
        assertEquals(0L, mismatch.absoluteOffset)
    }

    @Test
    fun `gzip produces bounded deferred bytes and rejects expansion bombs`() {
        val source = "bounded gzip payload".repeat(8).toByteArray()
        val packed = TlBytes.copyOf(gzip(source))
        assertArrayEquals(source, TlGzip.decompress(packed, context).toByteArray())

        val ratioContext = context.copy(limits = TlLimits.DEFAULT.lowered(maxDecompressedBytes = 4096, maxGzipRatio = 2))
        val ratioFailure = assertThrows(TlLimitExceededException::class.java) {
            TlGzip.decompress(TlBytes.copyOf(gzip(ByteArray(2048))), ratioContext)
        }
        assertEquals(TlLimitKind.GZIP_RATIO, ratioFailure.limitKind)

        val sizeContext = context.copy(limits = TlLimits.DEFAULT.lowered(maxDecompressedBytes = 32, maxGzipRatio = 100))
        val sizeFailure = assertThrows(TlLimitExceededException::class.java) {
            TlGzip.decompress(TlBytes.copyOf(gzip(ByteArray(64) { it.toByte() })), sizeContext)
        }
        assertEquals(TlLimitKind.DECOMPRESSED_BYTES, sizeFailure.limitKind)

        val corrupt = assertThrows(IllegalArgumentException::class.java) {
            TlGzip.decompress(TlBytes.copyOf(byteArrayOf(1, 2, 3)), context)
        }
        org.junit.Assert.assertTrue(corrupt.message.orEmpty().contains("Malformed gzip payload"))
        org.junit.Assert.assertTrue(corrupt.message.orEmpty().contains("TRANSPORT"))
    }

    @Test
    fun `random malformed inputs terminate without VM failures`() {
        val random = Random(0xBAD51A7L)
        repeat(1_000) {
            val bytes = ByteArray(random.nextInt(64)).also(random::nextBytes)
            runCatching { TlBinaryReader(bytes).readBytes(context) }.exceptionOrNull()?.let { failure ->
                require(failure !is VirtualMachineError)
                require(failure !is ThreadDeath)
            }
        }
    }

    private fun gzip(value: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(value) }
        output.toByteArray()
    }
}
