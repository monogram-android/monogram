package org.monogram.data.mtproto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.updates.MtProtoUpdateCursor

class MtProtoUpdateCursorCodecTest {
    @Test
    fun `round trips cursor fields`() {
        val cursor = MtProtoUpdateCursor(11, 22, 33, 44)

        assertEquals(cursor, MtProtoUpdateCursorCodec.decode(MtProtoUpdateCursorCodec.encode(cursor)))
    }

    @Test
    fun `rejects malformed records`() {
        assertThrows(IllegalArgumentException::class.java) {
            MtProtoUpdateCursorCodec.decode(ByteArray(24))
        }
        val bytes = MtProtoUpdateCursorCodec.encode(MtProtoUpdateCursor(1, 2, 3, 4))
        try {
            bytes[7] = 2
            assertThrows(IllegalArgumentException::class.java) { MtProtoUpdateCursorCodec.decode(bytes) }
        } finally {
            bytes.fill(0)
        }
    }
}
