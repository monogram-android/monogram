package org.monogram.mtproto.tl.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TlValuesTest {
    @Test
    fun `bytes copies input and output and uses content equality`() {
        val input = byteArrayOf(0x12, 0x34, 0x56)
        val value = TlBytes.copyOf(input)
        input[0] = 0
        val extracted = value.toByteArray()
        extracted[1] = 0

        assertArrayEquals(byteArrayOf(0x12, 0x34, 0x56), value.toByteArray())
        assertEquals(value, TlBytes.copyOf(byteArrayOf(0x12, 0x34, 0x56)))
        assertEquals(value.hashCode(), TlBytes.copyOf(value.toByteArray()).hashCode())
        assertNotEquals(value, TlBytes.copyOf(byteArrayOf(0x12, 0x34)))
        assertMetadataOnly(value.toString(), "TlBytes", 3)
    }

    @Test
    fun `fixed width values validate copy and redact`() {
        val source128 = ByteArray(16) { (it + 40).toByte() }
        val source256 = ByteArray(32) { (it + 80).toByte() }
        val int128 = TlInt128.copyOf(source128)
        val int256 = TlInt256.copyOf(source256)
        source128.fill(0)
        source256.fill(0)

        assertEquals(TlInt128.copyOf(ByteArray(16) { (it + 40).toByte() }), int128)
        assertEquals(TlInt256.copyOf(ByteArray(32) { (it + 80).toByte() }), int256)
        assertEquals(int128.hashCode(), TlInt128.copyOf(int128.toByteArray()).hashCode())
        assertEquals(int256.hashCode(), TlInt256.copyOf(int256.toByteArray()).hashCode())
        assertThrows(IllegalArgumentException::class.java) { TlInt128.copyOf(ByteArray(15)) }
        assertThrows(IllegalArgumentException::class.java) { TlInt128.copyOf(ByteArray(17)) }
        assertThrows(IllegalArgumentException::class.java) { TlInt256.copyOf(ByteArray(31)) }
        assertThrows(IllegalArgumentException::class.java) { TlInt256.copyOf(ByteArray(33)) }
        assertMetadataOnly(int128.toString(), "TlInt128", 16)
        assertMetadataOnly(int256.toString(), "TlInt256", 32)
    }

    @Test
    fun `deferred object is bounded copied content value with safe size`() {
        val input = byteArrayOf(101, 102, 103, 104)
        val deferred = TlDeferredObject.copyOf(input, maxBytes = 4)
        input.fill(0)
        val output = deferred.toByteArray()
        output.fill(1)

        assertEquals(4, deferred.size)
        assertArrayEquals(byteArrayOf(101, 102, 103, 104), deferred.toByteArray())
        assertEquals(deferred, TlDeferredObject.copyOf(byteArrayOf(101, 102, 103, 104), maxBytes = 8))
        assertEquals(deferred.hashCode(), TlDeferredObject.copyOf(deferred.toByteArray(), 4).hashCode())
        assertMetadataOnly(deferred.toString(), "TlDeferredObject", 4)
    }

    @Test
    fun `deferred object rejects invalid explicit bounds and oversized bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            TlDeferredObject.copyOf(ByteArray(0), maxBytes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TlDeferredObject.copyOf(ByteArray(0), maxBytes = TlLimits.DEFAULT.maxObjectBytes + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TlDeferredObject.copyOf(ByteArray(5), maxBytes = 4)
        }
        assertEquals(0, TlDeferredObject.copyOf(ByteArray(0), maxBytes = 1).size)
    }

    private fun assertMetadataOnly(rendered: String, type: String, size: Int) {
        assertEquals("$type(size=$size)", rendered)
        assertFalse(rendered.contains("101, 102"))
        assertFalse(rendered.contains("deadbeef", ignoreCase = true))
        assertFalse(rendered.contains("payload", ignoreCase = true))
    }
}
