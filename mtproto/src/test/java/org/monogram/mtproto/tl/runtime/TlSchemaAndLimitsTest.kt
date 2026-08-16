package org.monogram.mtproto.tl.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TlSchemaAndLimitsTest {
    @Test
    fun `supported schema identities preserve kind and layer`() {
        assertEquals(TlSchemaIdentity(TlSchemaKind.CLOUD, 223), cloud)
        assertEquals(TlSchemaIdentity(TlSchemaKind.TRANSPORT, null), transport)
        assertNull(transport.layer)
        assertEquals(
            supportedSecretLayers,
            supportedSecretLayers.map { TlSchemaIdentity(TlSchemaKind.SECRET, it).layer },
        )
    }

    @Test
    fun `schema identity enforces layer nullability by kind`() {
        assertThrows(IllegalArgumentException::class.java) { TlSchemaIdentity(TlSchemaKind.CLOUD, null) }
        assertThrows(IllegalArgumentException::class.java) { TlSchemaIdentity(TlSchemaKind.SECRET, null) }
        assertThrows(IllegalArgumentException::class.java) { TlSchemaIdentity(TlSchemaKind.TRANSPORT, 0) }
    }

    @Test
    fun `limits expose exact defaults and value semantics without data class rendering`() {
        val defaults = TlLimits.DEFAULT
        assertEquals(16 * 1024 * 1024, defaults.maxObjectBytes)
        assertEquals(16 * 1024 * 1024, defaults.maxDecompressedBytes)
        assertEquals(100_000, defaults.maxVectorElements)
        assertEquals(128, defaults.maxDepth)
        assertEquals(100, defaults.maxGzipRatio)
        assertEquals(TlLimits(), defaults)
        assertEquals(TlLimits().hashCode(), defaults.hashCode())
        assertNotEquals(defaults, defaults.lowered(maxDepth = 127))
        assertEquals(
            "TlLimits(maxObjectBytes=<redacted>, maxDecompressedBytes=<redacted>, " +
                "maxVectorElements=<redacted>, maxDepth=<redacted>, maxGzipRatio=<redacted>)",
            defaults.toString(),
        )
    }

    @Test
    fun `construction rejects every nonpositive and above-default limit`() {
        assertThrows(IllegalArgumentException::class.java) { TlLimits(maxObjectBytes = 0) }
        assertThrows(IllegalArgumentException::class.java) { TlLimits(maxDecompressedBytes = 0) }
        assertThrows(IllegalArgumentException::class.java) { TlLimits(maxVectorElements = 0) }
        assertThrows(IllegalArgumentException::class.java) { TlLimits(maxDepth = 0) }
        assertThrows(IllegalArgumentException::class.java) { TlLimits(maxGzipRatio = 0) }
        assertThrows(IllegalArgumentException::class.java) { TlLimits(maxObjectBytes = 16 * 1024 * 1024 + 1) }
        assertThrows(IllegalArgumentException::class.java) { TlLimits(maxDecompressedBytes = 16 * 1024 * 1024 + 1) }
        assertThrows(IllegalArgumentException::class.java) { TlLimits(maxVectorElements = 100_001) }
        assertThrows(IllegalArgumentException::class.java) { TlLimits(maxDepth = 129) }
        assertThrows(IllegalArgumentException::class.java) { TlLimits(maxGzipRatio = 101) }
    }

    @Test
    fun `lowered changes every field and rejects raising every field`() {
        val lowered = TlLimits.DEFAULT.lowered(
            maxObjectBytes = 1,
            maxDecompressedBytes = 2,
            maxVectorElements = 3,
            maxDepth = 4,
            maxGzipRatio = 5,
        )
        assertEquals(TlLimits(1, 2, 3, 4, 5), lowered)

        assertThrows(IllegalArgumentException::class.java) { lowered.lowered(maxObjectBytes = 2) }
        assertThrows(IllegalArgumentException::class.java) { lowered.lowered(maxDecompressedBytes = 3) }
        assertThrows(IllegalArgumentException::class.java) { lowered.lowered(maxVectorElements = 4) }
        assertThrows(IllegalArgumentException::class.java) { lowered.lowered(maxDepth = 5) }
        assertThrows(IllegalArgumentException::class.java) { lowered.lowered(maxGzipRatio = 6) }
    }

    @Test
    fun `nested increments once through last depth then fails before returning`() {
        val limits = TlLimits.DEFAULT.lowered(maxDepth = 2)
        val root = TlDecodeContext(cloud, depth = 0, limits = limits)
        val first = root.nested()
        val last = first.nested()

        assertEquals(0, root.depth)
        assertEquals(1, first.depth)
        assertEquals(2, last.depth)
        val failure = assertThrows(TlLimitExceededException::class.java) { last.nested() }
        assertEquals(TlLimitKind.DEPTH, failure.limitKind)
        assertEquals(2, failure.configuredMaximum)
        assertEquals(3, failure.observedValue)
        assertEquals(cloud, failure.schema)
        assertNull(failure.absoluteOffset)
        assertNull(failure.constructorId)
    }

    private companion object {
        val cloud = TlSchemaIdentity(TlSchemaKind.CLOUD, 223)
        val transport = TlSchemaIdentity(TlSchemaKind.TRANSPORT, null)
        val supportedSecretLayers = listOf(8, 17, 20, 23, 45, 46, 66, 73, 101, 143, 144, 216)
    }
}
