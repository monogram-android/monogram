package org.monogram.feature.dialog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.feature.dialog.ui.stillImageMagic

class StillImageTest {
    @Test
    fun jpegPngGifWebpAreImages() {
        assertTrue(stillImageMagic(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertTrue(
            stillImageMagic(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            ),
        )
        assertTrue(stillImageMagic("GIF89a".toByteArray()))
        val webp = ByteArray(12)
        "RIFF".toByteArray().copyInto(webp, 0)
        "WEBP".toByteArray().copyInto(webp, 8)
        assertTrue(stillImageMagic(webp))
    }

    @Test
    fun mp4AndEmptyAreNotImages() {
        assertFalse(stillImageMagic(byteArrayOf()))
        assertFalse(stillImageMagic("ftypisom".toByteArray()))
        val mp4 = ByteArray(12)
        mp4[4] = 'f'.code.toByte()
        mp4[5] = 't'.code.toByte()
        mp4[6] = 'y'.code.toByte()
        mp4[7] = 'p'.code.toByte()
        assertFalse(stillImageMagic(mp4))
    }
}
