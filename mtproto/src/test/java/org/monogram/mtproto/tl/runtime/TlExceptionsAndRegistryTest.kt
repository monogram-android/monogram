package org.monogram.mtproto.tl.runtime

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test

class TlExceptionsAndRegistryTest {
    @Test
    fun `unknown constructor keeps UInt identity and reader offset`() {
        val failure = TlUnknownConstructorException(cloud, UInt.MAX_VALUE, 41L)

        assertEquals(cloud, failure.schema)
        assertEquals(UInt.MAX_VALUE, failure.constructorId)
        assertEquals(41L, failure.absoluteOffset)
        assertEquals(true, failure.message!!.contains("4294967295"))
        assertFalse(failure.message!!.contains("-1"))
    }

    @Test
    fun `limit exception exposes only safe typed observations`() {
        val failure = TlLimitExceededException(
            schema = cloud,
            limitKind = TlLimitKind.OBJECT_BYTES,
            configuredMaximum = 1024,
            observedValue = 1025,
            absoluteOffset = 99L,
        )

        assertEquals(cloud, failure.schema)
        assertEquals(TlLimitKind.OBJECT_BYTES, failure.limitKind)
        assertEquals(1024, failure.configuredMaximum)
        assertEquals(1025, failure.observedValue)
        assertEquals(99L, failure.absoluteOffset)
        assertNull(failure.constructorId)
        assertFalse(failure.message!!.contains("secret-payload"))
    }

    @Test
    fun `compliant fake registry checks schema before consumption`() {
        val reader = NoConsumptionReader(absoluteOffset = 73L)
        val registry = CompliantFakeRegistry(actualSchema = transport)
        val context = TlDecodeContext(cloud, 0, TlLimits.DEFAULT)

        val failure = assertThrows(TlSchemaMismatchException::class.java) {
            registry.decode(0xabcdef01u, reader, context)
        }

        assertEquals(cloud, failure.expectedSchema)
        assertEquals(transport, failure.actualSchema)
        assertEquals(cloud, failure.schema)
        assertEquals(73L, failure.absoluteOffset)
        assertNull(failure.constructorId)
        assertEquals(0, reader.readAttempts)
    }

    @Test
    fun `compliant fake registry uses validated schema for unknown constructor`() {
        val reader = NoConsumptionReader(absoluteOffset = 88L)
        val registry = CompliantFakeRegistry(actualSchema = cloud)
        val context = TlDecodeContext(cloud, 0, TlLimits.DEFAULT)

        val failure = assertThrows(TlUnknownConstructorException::class.java) {
            registry.decode(UInt.MAX_VALUE, reader, context)
        }

        assertEquals(cloud, failure.schema)
        assertEquals(UInt.MAX_VALUE, failure.constructorId)
        assertEquals(88L, failure.absoluteOffset)
        assertEquals(0, reader.readAttempts)
    }

    @Test
    fun `codec exception construction is sealed to typed metadata and payload free`() {
        val payloadSentinel = "SENTINEL-secret-payload"
        val failures = listOf(
            TlUnknownConstructorException(cloud, UInt.MAX_VALUE, 41L),
            TlLimitExceededException(cloud, TlLimitKind.OBJECT_BYTES, 1024, 1025, 99L),
            TlSchemaMismatchException(cloud, transport, 73L),
        )

        val exceptionTypes = listOf(
            TlCodecException::class.java,
            TlUnknownConstructorException::class.java,
            TlLimitExceededException::class.java,
            TlSchemaMismatchException::class.java,
        )
        exceptionTypes.flatMap { it.declaredConstructors.toList() }
            .filter { Modifier.isPublic(it.modifiers) }
            .forEach { constructor ->
                assertFalse(constructor.parameterTypes.contains(String::class.java))
                assertFalse(constructor.parameterTypes.any { Throwable::class.java.isAssignableFrom(it) })
            }

        failures.forEach { failure ->
            assertNull(failure.cause)
            assertFalse(failure.message.orEmpty().contains(payloadSentinel))
            assertFalse(failure.toString().contains(payloadSentinel))
        }
        val payloadCause = IllegalStateException(payloadSentinel)
        assertThrows(IllegalStateException::class.java) {
            failures.first().initCause(payloadCause)
        }
        assertNull(failures.first().cause)
        assertFalse(failures.first().toString().contains(payloadSentinel))
    }

    private class CompliantFakeRegistry(
        private val actualSchema: TlSchemaIdentity,
    ) : TlConstructorRegistry {
        override val schema: TlSchemaIdentity = actualSchema

        override fun decode(id: UInt, reader: TlReader, context: TlDecodeContext): TlObject {
            if (schema != context.schema) {
                throw TlSchemaMismatchException(
                    expectedSchema = context.schema,
                    actualSchema = schema,
                    absoluteOffset = reader.absoluteOffset,
                )
            }
            throw TlUnknownConstructorException(context.schema, id, reader.absoluteOffset)
        }
    }

    private class NoConsumptionReader(
        override val absoluteOffset: Long,
    ) : TlReader {
        var readAttempts: Int = 0
            private set

        override val size: Long = 0

        private fun consumed(): Nothing {
            readAttempts += 1
            fail("Registry consumed payload before rejecting")
            error("unreachable")
        }

        override fun readInt(): Int = consumed()
        override fun readLong(): Long = consumed()
        override fun readDouble(): Double = consumed()
        override fun readBool(context: TlDecodeContext): Boolean = consumed()
        override fun readBytes(context: TlDecodeContext): TlBytes = consumed()
        override fun readString(context: TlDecodeContext): String = consumed()
        override fun readInt128(): TlInt128 = consumed()
        override fun readInt256(): TlInt256 = consumed()
        override fun readDeferredObject(byteCount: Int, context: TlDecodeContext): TlDeferredObject = consumed()
        override fun readRemainingDeferredObject(context: TlDecodeContext): TlDeferredObject = consumed()
        override fun <T> readVector(codec: TlCodec<T>, context: TlDecodeContext): List<T> = consumed()
    }

    private companion object {
        val cloud = TlSchemaIdentity(TlSchemaKind.CLOUD, 223)
        val transport = TlSchemaIdentity(TlSchemaKind.TRANSPORT, null)
    }
}
