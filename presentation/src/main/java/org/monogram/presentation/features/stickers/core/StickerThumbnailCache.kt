package org.monogram.presentation.features.stickers.core

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap

object StickerThumbnailCache {
    private const val CACHE_SIZE = 150

    private val cache = object : LruCache<String, ImageBitmap>(CACHE_SIZE) {
        override fun entryRemoved(
            evicted: Boolean,
            key: String?,
            oldValue: ImageBitmap?,
            newValue: ImageBitmap?
        ) {
        }
    }

    fun get(key: String): ImageBitmap? {
        return cache.get(key)
    }

    fun put(key: String, bitmap: ImageBitmap) {
        cache.put(key, bitmap)
    }

    fun clear() {
        cache.evictAll()
    }
}
