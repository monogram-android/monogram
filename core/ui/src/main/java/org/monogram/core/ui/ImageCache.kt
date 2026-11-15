package org.monogram.core.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import coil.Coil
import coil.EventListener
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.compose.LocalImageLoader
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Coil caches are owned by the media root of each account. */
object ImageCache {
    const val DISK_DIR = "coil"
    const val MAX_DISK_BYTES = 256L * 1024L * 1024L

    @Volatile
    private var loader: ImageLoader? = null

    private val accountLoaders = ConcurrentHashMap<String, ImageLoader>()

    @Volatile
    private var accountFilesDir: File? = null

    @Synchronized
    internal fun accountLoader(context: Context, root: File): ImageLoader =
        accountLoaders.getOrPut(root.canonicalPath) {
            val app = context.applicationContext
            accountFilesDir = app.filesDir
            ImageLoader.Builder(app)
                .memoryCache { MemoryCache.Builder(app).maxSizePercent(0.05).build() }.diskCache {
                    DiskCache.Builder().directory(accountDiskDir(app.filesDir, root))
                        .maxSizeBytes(MAX_DISK_BYTES).build()
                }.respectCacheHeaders(false).components { add(VideoFrameDecoder.Factory()) }
                .eventListener(leaseListener).crossfade(false).build()
        }

    @Suppress("DEPRECATION")
    @Composable
    fun AccountScope(root: File, content: @Composable () -> Unit) {
        val loader = accountLoader(LocalContext.current, root)
        CompositionLocalProvider(LocalImageLoader provides loader, content = content)
    }

    fun diskDir(filesDir: File): File = File(filesDir, DISK_DIR)

    fun install(context: Context) {
        val app = context.applicationContext
        accountFilesDir = app.filesDir
        val created = ImageLoader.Builder(app).memoryCache {
            MemoryCache.Builder(app).maxSizePercent(0.25).build()
        }.diskCache {
            DiskCache.Builder().directory(diskDir(app.filesDir)).maxSizeBytes(MAX_DISK_BYTES)
                .build()
        }.respectCacheHeaders(false).components { add(VideoFrameDecoder.Factory()) }
            .eventListener(leaseListener).crossfade(false).build()
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

    fun release(root: File) {
        clear(root)
        accountLoaders.remove(root.canonicalPath)?.shutdown()
        accountDiskDir(root)?.deleteRecursively()
    }

    @OptIn(ExperimentalCoilApi::class)
    fun clear(root: File?) {
        val accountRoot = root ?: return
        accountLoaders[accountRoot.canonicalPath]?.let { account ->
            account.memoryCache?.clear()
            account.diskCache?.clear()
        } ?: accountDiskDir(accountRoot)?.deleteRecursively()
    }

    private fun accountDiskDir(filesDir: File, root: File): File {
        val name = MessageDigest.getInstance("SHA-256").digest(root.canonicalPath.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(filesDir, "coil-accounts/$name")
    }

    private fun accountDiskDir(root: File): File? {
        val filesDir = accountFilesDir ?: return null
        return accountDiskDir(filesDir, root)
    }
}
