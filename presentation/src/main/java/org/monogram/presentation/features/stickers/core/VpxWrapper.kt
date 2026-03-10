package org.monogram.presentation.features.stickers.core

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import java.io.File

class VpxWrapper {
    private var nativePtr: Long = 0
    private var pfd: ParcelFileDescriptor? = null

    init {
        System.loadLibrary("native-lib")
        nativePtr = create()
    }

    fun open(file: File): Boolean {
        return try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            open(nativePtr, pfd!!.fd, 0, file.length())
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun renderFrame(bitmap: Bitmap): Long {
        if (nativePtr == 0L) return -1
        return decodeNextFrame(nativePtr, bitmap)
    }

    fun release() {
        if (nativePtr != 0L) {
            destroy(nativePtr)
            nativePtr = 0
        }
        pfd?.close()
    }

    fun getVideoWidth(): Int = getWidth(nativePtr)
    fun getVideoHeight(): Int = getHeight(nativePtr)

    private external fun create(): Long
    private external fun open(ptr: Long, fd: Int, offset: Long, length: Long): Boolean
    private external fun decodeNextFrame(ptr: Long, bitmap: Bitmap): Long
    private external fun destroy(ptr: Long)

    private external fun getWidth(ptr: Long): Int
    private external fun getHeight(ptr: Long): Int
}