package org.monogram.presentation.features.stickers.core

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*

object BitmapPool {
    private val pool = LinkedList<Bitmap>()
    private val mutex = Mutex()
    private const val MAX_POOL_SIZE = 50

    suspend fun obtain(width: Int, height: Int): Bitmap {
        mutex.withLock {
            val iterator = pool.iterator()
            while (iterator.hasNext()) {
                val bitmap = iterator.next()
                if (bitmap.width == width && bitmap.height == height) {
                    iterator.remove()
                    return bitmap
                }
            }
        }
        return createBitmap(width, height)
    }

    suspend fun recycle(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        mutex.withLock {
            if (pool.size < MAX_POOL_SIZE) {
                pool.add(bitmap)
            } else {
                bitmap.recycle()
            }
        }
    }

    suspend fun clear() {
        mutex.withLock {
            pool.forEach { it.recycle() }
            pool.clear()
        }
    }
}