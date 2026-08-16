package org.monogram.mtproto.codec

import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.InvokeAfterMsg
import org.monogram.mtproto.tl.generated.cloud.layer223.InvokeAfterMsgCodec
import org.monogram.mtproto.tl.generated.cloud.layer223.account.CancelPasswordEmail
import org.monogram.mtproto.tl.generated.cloud.layer223.account.CancelPasswordEmailResultCodec
import org.monogram.mtproto.tl.generated.cloud.layer223.registry.CloudLayer223ConstructorRegistry
import org.monogram.mtproto.tl.generated.transport.DestroyAuthKeyNone
import org.monogram.mtproto.tl.generated.transport.DestroyAuthKeyNoneCodec
import org.monogram.mtproto.tl.generated.transport.FutureSalt_0cbddf76ed
import org.monogram.mtproto.tl.generated.transport.FutureSalt_0cbddf76edCodec
import org.monogram.mtproto.tl.generated.transport.FutureSalt_d07b700fe3BoxedCodec
import org.monogram.mtproto.tl.generated.transport.FutureSalts_9e3c917caa
import org.monogram.mtproto.tl.generated.transport.FutureSalts_9e3c917caaCodec
import org.monogram.mtproto.tl.generated.transport.Ping
import org.monogram.mtproto.tl.generated.transport.PingCodec
import org.monogram.mtproto.tl.generated.transport.Pong_fbc65fe5b1
import org.monogram.mtproto.tl.generated.transport.registry.TransportConstructorRegistry
import org.monogram.mtproto.tl.generated.secret.layer143.DocumentAttributeAudio
import org.monogram.mtproto.tl.generated.secret.layer143.DocumentAttributeAudioCodec
import org.monogram.mtproto.tl.generated.secret.layer143.DocumentAttributeBoxedCodec
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.tl.runtime.TlDecodeContext
import org.monogram.mtproto.tl.runtime.TlLimitExceededException
import org.monogram.mtproto.tl.runtime.TlLimitKind
import org.monogram.mtproto.tl.runtime.TlLimits
import org.monogram.mtproto.tl.runtime.TlSchemaIdentity
import org.monogram.mtproto.tl.runtime.TlSchemaKind
import org.monogram.mtproto.tl.runtime.TlSchemaMismatchException
import org.monogram.mtproto.tl.runtime.TlUnknownConstructorException

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

        val unknownBytes = byteArrayOf(1, 2, 3, 4)
        val registryFailure = assertThrows(TlUnknownConstructorException::class.java) {
            TlBinaryCodec.decodeObject(TransportConstructorRegistry, unknownBytes, context)
        }
        val codecFailure = assertThrows(TlUnknownConstructorException::class.java) {
            TlBinaryCodec.decode(DestroyAuthKeyNoneCodec, unknownBytes, context)
        }
        assertEquals(0L, registryFailure.absoluteOffset)
        assertEquals(codecFailure.absoluteOffset, registryFailure.absoluteOffset)
    }

    @Test
    fun `generated fields boxed family and vectors round trip through binary runtime`() {
        val first = FutureSalt_0cbddf76ed(1, 2, 3L)
        val second = FutureSalt_0cbddf76ed(-4, 5, Long.MIN_VALUE)
        assertEquals(first, TlBinaryCodec.decode(FutureSalt_0cbddf76edCodec, TlBinaryCodec.encode(FutureSalt_0cbddf76edCodec, first), context))
        assertEquals(first, TlBinaryCodec.decode(FutureSalt_d07b700fe3BoxedCodec, TlBinaryCodec.encode(FutureSalt_d07b700fe3BoxedCodec, first), context))

        val vectorWriter = TlBinaryWriter()
        vectorWriter.writeVector(listOf(first, second), FutureSalt_0cbddf76edCodec)
        val decoded = TlBinaryReader(vectorWriter.toByteArray()).readVector(FutureSalt_0cbddf76edCodec, context)
        assertEquals(listOf(first, second), decoded)
    }

    @Test
    fun `generated optional flags bare and boxed codecs round trip`() {
        val secretContext = TlDecodeContext(TlSchemaIdentity(TlSchemaKind.SECRET, 143), 0, TlLimits.DEFAULT)
        val populated = DocumentAttributeAudio(
            voice = true,
            duration = 123,
            title = "title",
            performer = "performer",
            waveform = TlBytes.copyOf(byteArrayOf(1, 2, 3, 4)),
        )
        val empty = DocumentAttributeAudio(
            voice = false,
            duration = 0,
            title = null,
            performer = null,
            waveform = null,
        )

        listOf(populated, empty).forEach { value ->
            val bareWriter = TlBinaryWriter()
            DocumentAttributeAudioCodec.writeBare(bareWriter, value)
            val bareBytes = bareWriter.toByteArray()
            val expectedFlags = if (value === populated) 0x407 else 0
            assertEquals(expectedFlags, TlBinaryReader(bareBytes, schema = secretContext.schema).readInt())
            val bareReader = TlBinaryReader(bareBytes, schema = secretContext.schema)
            assertEquals(value, DocumentAttributeAudioCodec.readBare(bareReader, secretContext))
            assertEquals(0, bareReader.remaining)

            val boxed = TlBinaryCodec.encode(DocumentAttributeBoxedCodec, value)
            assertEquals(value, TlBinaryCodec.decode(DocumentAttributeBoxedCodec, boxed, secretContext))
        }
    }

    @Test
    fun `generated method request and result codecs round trip`() {
        val request = Ping(Long.MIN_VALUE)
        assertEquals(request, TlBinaryCodec.decode(PingCodec, TlBinaryCodec.encode(PingCodec, request), context))

        val response = Pong_fbc65fe5b1(msgId = Long.MAX_VALUE, pingId = request.pingId)
        val encoded = TlBinaryCodec.encode(request.resultCodec, response)
        assertEquals(response, TlBinaryCodec.decode(request.resultCodec, encoded, context))
    }

    @Test
    fun `generated vector of bare objects and registry dispatch round trip`() {
        val salt = FutureSalt_0cbddf76ed(validSince = 1, validUntil = 2, salt = 3L)
        val salts = FutureSalts_9e3c917caa(reqMsgId = 9L, now = 10, salts = listOf(salt))
        assertEquals(salts, TlBinaryCodec.decode(FutureSalts_9e3c917caaCodec, TlBinaryCodec.encode(FutureSalts_9e3c917caaCodec, salts), context))

        val encodedSalt = TlBinaryCodec.encode(FutureSalt_0cbddf76edCodec, salt)
        assertEquals(salt, TlBinaryCodec.decodeObject(TransportConstructorRegistry, encodedSalt, context))
    }

    @Test
    fun `generated generic method and bound result codec round trip`() {
        val cloudContext = TlDecodeContext(TlSchemaIdentity(TlSchemaKind.CLOUD, 223), 0, TlLimits.DEFAULT)
        val request = InvokeAfterMsg(msgId = 42L, query = CancelPasswordEmail)
        val codec = InvokeAfterMsgCodec.bind(CancelPasswordEmailResultCodec)
        assertEquals(request, TlBinaryCodec.decode(codec, TlBinaryCodec.encode(codec, request), cloudContext))

        val registryWriter = TlBinaryWriter()
        CloudLayer223ConstructorRegistry.encodeMethod(registryWriter, request)
        val registryBytes = registryWriter.toByteArray()
        val registryReader = TlBinaryReader(registryBytes, schema = cloudContext.schema)
        val methodId = registryReader.readInt().toUInt()
        assertEquals(
            request,
            CloudLayer223ConstructorRegistry.decodeMethod(
                methodId,
                registryReader,
                cloudContext,
                CancelPasswordEmailResultCodec,
            ),
        )
        assertEquals(0, registryReader.remaining)

        val unboundReader = TlBinaryReader(registryBytes, schema = cloudContext.schema)
        val unboundId = unboundReader.readInt().toUInt()
        val unboundFailure = assertThrows(IllegalArgumentException::class.java) {
            CloudLayer223ConstructorRegistry.decodeMethod(unboundId, unboundReader, cloudContext)
        }
        org.junit.Assert.assertTrue(unboundFailure.message.orEmpty().contains("requires an explicit result codec"))
        assertEquals(registryBytes.size - Int.SIZE_BYTES, unboundReader.remaining)

        val encodedResult = TlBinaryCodec.encode(request.resultCodec, true)
        assertEquals(true, TlBinaryCodec.decode(request.resultCodec, encodedResult, cloudContext))
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

        val objectContext = context.copy(
            limits = TlLimits.DEFAULT.lowered(maxObjectBytes = 32, maxDecompressedBytes = 4096, maxGzipRatio = 100),
        )
        val objectFailure = assertThrows(TlLimitExceededException::class.java) {
            TlGzip.decompress(TlBytes.copyOf(gzip(ByteArray(64) { it.toByte() })), objectContext)
        }
        assertEquals(TlLimitKind.OBJECT_BYTES, objectFailure.limitKind)

        val trailingJunk = gzip(ByteArray(2048)) + ByteArray(16 * 1024)
        val trailingFailure = assertThrows(TlLimitExceededException::class.java) {
            TlGzip.decompress(TlBytes.copyOf(trailingJunk), ratioContext)
        }
        assertEquals(TlLimitKind.GZIP_RATIO, trailingFailure.limitKind)

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
