package org.monogram.core.ui.components

import android.graphics.Bitmap

/** Converts a native RGBA frame to an ARGB bitmap; call off the main thread. */
fun argbFrameBitmap(rgba: ByteArray, width: Int, height: Int): Bitmap? {
    if (width <= 0 || height <= 0) return null
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val argb = IntArray(width * height)
    var i = 0
    var p = 0
    while (i + 3 < rgba.size && p < argb.size) {
        val r = rgba[i].toInt() and 0xFF
        val g = rgba[i + 1].toInt() and 0xFF
        val b = rgba[i + 2].toInt() and 0xFF
        val a = rgba[i + 3].toInt() and 0xFF
        argb[p] = (a shl 24) or (r shl 16) or (g shl 8) or b
        i += 4
        p += 1
    }
    bitmap.setPixels(argb, 0, width, 0, 0, width, height)
    return bitmap
}
