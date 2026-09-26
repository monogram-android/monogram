package org.monogram.core.ui.components

import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameBitmapTest {
    @Test
    fun convertsRgbaToArgbPixels() {
        val rgba = byteArrayOf(
            10, 20, 30, -1,
            40, 50, 60, -1,
        )
        val bitmap = requireNotNull(argbFrameBitmap(rgba, 2, 1))
        val pixels = bitmap.asImageBitmap().toPixelMap()
        assertEquals(2, pixels.width)
        assertEquals(1, pixels.height)
        assertEquals(10, (pixels[0, 0].red * 255f).toInt())
        assertEquals(20, (pixels[0, 0].green * 255f).toInt())
        assertEquals(30, (pixels[0, 0].blue * 255f).toInt())
        assertEquals(40, (pixels[1, 0].red * 255f).toInt())
        assertEquals(50, (pixels[1, 0].green * 255f).toInt())
        assertEquals(60, (pixels[1, 0].blue * 255f).toInt())
    }

    @Test
    fun rejectsEmptyFramesAndShortBuffers() {
        assertNull(argbFrameBitmap(ByteArray(0), 0, 0))
        assertNull(argbFrameBitmap(ByteArray(0), -1, 4))
        val bitmap = requireNotNull(argbFrameBitmap(byteArrayOf(1, 2, 3, 4), 4, 2))
        assertEquals(4, bitmap.width)
        assertEquals(2, bitmap.height)
    }
}
