package org.monogram.mtproto.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IntermediateTransportFramingTest {
    @Test
    fun encodesPreambleLengthAndPayload() {
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val encoded = IntermediateTransportFraming.encode(payload, includePreamble = true)
        assertArrayEquals(byteArrayOf(0xee.toByte(), 0xee.toByte(), 0xee.toByte(), 0xee.toByte()), encoded.copyOfRange(0, 4))
        assertArrayEquals(byteArrayOf(8, 0, 0, 0), encoded.copyOfRange(4, 8))
        val decoded = IntermediateTransportFraming.decode(encoded.copyOfRange(4, encoded.size)) as IntermediateFrame.Message
        assertArrayEquals(payload, decoded.payload)
        assertEquals(encoded.size - 4, IntermediateTransportFraming.expectedFrameBytes(encoded.copyOfRange(4, 8)))
    }

    @Test
    fun decodesQuickAckAsHeaderOnlyFrame() {
        val frame = byteArrayOf(0x78, 0x56, 0x34, 0x92.toByte())
        assertEquals(4, IntermediateTransportFraming.expectedFrameBytes(frame))
        assertEquals(0x92345678u, (IntermediateTransportFraming.decode(frame) as IntermediateFrame.QuickAck).token)
    }

    @Test
    fun rejectsMalformedLengthsAndFrames() {
        assertThrows(IllegalArgumentException::class.java) { IntermediateTransportFraming.encode(ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { IntermediateTransportFraming.encode(ByteArray(5)) }
        assertThrows(IllegalArgumentException::class.java) {
            IntermediateTransportFraming.expectedFrameBytes(byteArrayOf(5, 0, 0, 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            IntermediateTransportFraming.decode(byteArrayOf(4, 0, 0, 0, 1, 2, 3))
        }
    }
}
