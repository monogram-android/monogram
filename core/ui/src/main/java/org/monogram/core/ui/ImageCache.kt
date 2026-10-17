package org.monogram.core.ui

import android.content.Context
import coil.Coil
import coil.EventListener
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Process-wide Coil memory + durable disk cache for avatars, thumbs, and photos. */
object ImageCache {
    const val DISK_DIR = "coil"
    const val MAX_DISK_BYTES = 256L * 1024L * 1024L

    @Volatile
    private var loader: ImageLoader? = null

    fun diskDir(filesDir: File): File = File(filesDir, DISK_DIR)

    fun install(context: Context) {
        val app = context.applicationContext
        val created = ImageLoader.Builder(app)
            .memoryCache {
                MemoryCache.Builder(app).maxSizePercent(0.25).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(diskDir(app.filesDir))
                    .maxSizeBytes(MAX_DISK_BYTES)
                    .build()
            }
            .respectCacheHeaders(false)
            .components { add(VideoFrameDecoder.Factory()) }
            .eventListener(leaseListener)
            .crossfade(false)
            .build()
        loader = created
        Coil.setImageLoader(created)
    }

    private val leases = ConcurrentHashMap<ImageRequest, AutoCloseable>()

    private val leaseListener = object : EventListener {
        override fun onStart(request: ImageRequest) {
            val file = request.data as? File ?: return
            FileLeases.acquire(file)?.let { leases[request] = it }
        }

        override fun onSuccess(request: ImageRequest, result: SuccessResult) {
            leases.remove(request)?.close()
        }

        override fun onError(request: ImageRequest, result: ErrorResult) {
            leases.remove(request)?.close()
        }

        override fun onCancel(request: ImageRequest) {
            leases.remove(request)?.close()
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    fun clear() {
        loader?.memoryCache?.clear()
        loader?.diskCache?.clear()
    }
}
