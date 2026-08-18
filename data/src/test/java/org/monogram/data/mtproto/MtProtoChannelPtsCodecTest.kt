package org.monogram.data.mtproto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MtProtoChannelPtsCodecTest {
    @Test
    fun `round trips channel cursors deterministically`() {
        val cursors = linkedMapOf(200L to 9, 100L to 7)

        assertEquals(cursors, MtProtoChannelPtsCodec.decode(MtProtoChannelPtsCodec.encode(cursors)))
        assertEquals(
            MtProtoChannelPtsCodec.encode(linkedMapOf(100L to 7, 200L to 9)),
            MtProtoChannelPtsCodec.encode(cursors),
        )
    }

    @Test
    fun `rejects duplicate and invalid channel cursors`() {
        assertThrows(IllegalArgumentException::class.java) {
            MtProtoChannelPtsCodec.decode("[{\"channelId\":1,\"pts\":2},{\"channelId\":1,\"pts\":3}]")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MtProtoChannelPtsCodec.decode("[{\"channelId\":0,\"pts\":2}]")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MtProtoChannelPtsCodec.decode("[{\"channelId\":1,\"pts\":-1}]")
        }
    }
}
