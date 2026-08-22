package org.monogram.mtproto.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateShortSentMessage

class CloudTlObjectCodecTest {
    @Test
    fun `round trips boxed cloud object`() {
        val update = UpdateShortSentMessage(true, 17, 23, 1, 31, null, null, null)

        assertEquals(update, CloudTlObjectCodec.decode(CloudTlObjectCodec.encode(update)))
    }

    @Test
    fun `rejects truncated cloud object`() {
        val encoded = CloudTlObjectCodec.encode(
            UpdateShortSentMessage(true, 17, 23, 1, 31, null, null, null)
        )
        assertThrows(IllegalArgumentException::class.java) {
            CloudTlObjectCodec.decode(encoded.copyOf(encoded.size - 1))
        }
    }
}
