package org.monogram.presentation.features.stickers.core

import android.graphics.Bitmap
import org.monogram.presentation.core.util.coRunCatching
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

class RLottieWrapper {
    private var nativePtr: Long = 0

    init {
        if (isNativeLibraryLoaded) {
            nativePtr = coRunCatching { create() }.getOrDefault(0L)
        }
    }

    fun open(file: File): Boolean {
        if (!file.exists()) return false
        if (nativePtr == 0L) return false

        return coRunCatching {
            val json = if (file.isGzipped()) {
                GZIPInputStream(FileInputStream(file)).bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                file.readText(Charsets.UTF_8)
            }

            val cacheKey = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
            val resourcePath = file.parent ?: ""
            openFromData(nativePtr, json, cacheKey, resourcePath)
        }.getOrDefault(false)
    }

    fun renderFrame(
        bitmap: Bitmap,
        frameNo: Int,
        drawLeft: Int,
        drawTop: Int,
        drawWidth: Int,
        drawHeight: Int
    ): Boolean {
        if (nativePtr == 0L) return false
        return renderFrame(nativePtr, bitmap, frameNo, drawLeft, drawTop, drawWidth, drawHeight)
    }

    fun getWidth(): Int = if (nativePtr == 0L) 0 else getWidth(nativePtr)
    fun getHeight(): Int = if (nativePtr == 0L) 0 else getHeight(nativePtr)
    fun getTotalFrames(): Int = if (nativePtr == 0L) 0 else getTotalFrames(nativePtr)
    fun getFrameRate(): Double = if (nativePtr == 0L) 0.0 else getFrameRate(nativePtr)
    fun getDurationMs(): Long = if (nativePtr == 0L) 0L else getDurationMs(nativePtr)

    fun release() {
        if (nativePtr == 0L) return
        destroy(nativePtr)
        nativePtr = 0L
    }

    private fun File.isGzipped(): Boolean {
        if (!exists() || length() < 2L) return false
        return coRunCatching {
            FileInputStream(this).use { fis ->
                val low = fis.read()
                val high = fis.read()
                low == 0x1f && high == 0x8b
            }
        }.getOrDefault(false)
    }

    private external fun create(): Long
    private external fun openFromData(ptr: Long, json: String, cacheKey: String, resourcePath: String): Boolean
    private external fun renderFrame(
        ptr: Long,
        bitmap: Bitmap,
        frameNo: Int,
        drawLeft: Int,
        drawTop: Int,
        drawWidth: Int,
        drawHeight: Int
    ): Boolean

    private external fun getWidth(ptr: Long): Int
    private external fun getHeight(ptr: Long): Int
    private external fun getTotalFrames(ptr: Long): Int
    private external fun getFrameRate(ptr: Long): Double
    private external fun getDurationMs(ptr: Long): Long
    private external fun destroy(ptr: Long)

    companion object {
        private val isNativeLibraryLoaded: Boolean by lazy {
            coRunCatching {
                System.loadLibrary("native-lib")
            }.isSuccess
        }
    }
}
