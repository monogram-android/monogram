package org.monogram.presentation.settings.storage

import android.content.Context
import coil3.imageLoader
import org.monogram.presentation.core.media.ExoPlayerCache
import java.io.File

class CacheController(val context: Context, val exoPlayerCache: ExoPlayerCache) {
    fun clearExo() {
        exoPlayerCache.clearCache(context)
    }

    fun clearImageLoader() {
        context.imageLoader.memoryCache?.clear()
        context.imageLoader.diskCache?.clear()
    }

    fun clearAllCache() {
        clearExo()
        clearImageLoader()
    }

    fun getCacheDir(): File? {
        return context.cacheDir
    }
}