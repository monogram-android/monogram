package org.monogram.presentation.settings.storage

import android.content.Context
import coil3.imageLoader
import org.monogram.presentation.core.media.ExoPlayerCache
import java.io.File

data class AppTempCacheUsage(
    val size: Long,
    val fileCount: Int
) {
    val isEmpty: Boolean get() = size <= 0L || fileCount <= 0
}

class CacheController(val context: Context, val exoPlayerCache: ExoPlayerCache) {
    private val tdlibCacheRootName = "tdlib"
    private val appTempFilesDirPrefixes = listOf("CIRCLE_FULL_")

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
        clearAppTempFiles()
        clearAppInternalTempFiles()
    }

    fun getAppTempUsage(): AppTempCacheUsage {
        val files = buildList {
            collectCacheTempFiles(context.cacheDir, this)
            context.externalCacheDir?.let { collectCacheTempFiles(it, this) }
            collectInternalTempFiles(context.filesDir, this)
        }
        return AppTempCacheUsage(
            size = files.sumOf(File::length),
            fileCount = files.size
        )
    }

    fun getCacheDir(): File {
        return context.cacheDir
    }

    private fun clearAppTempFiles() {
        clearDirectoryChildren(context.cacheDir)
        context.externalCacheDir?.let(::clearDirectoryChildren)
    }

    private fun clearDirectoryChildren(directory: File) {
        val children = directory.listFiles() ?: return
        children.forEach { child ->
            if (child.name == tdlibCacheRootName) {
                return@forEach
            }
            child.deleteRecursively()
        }
    }

    private fun collectCacheTempFiles(directory: File, destination: MutableList<File>) {
        val children = directory.listFiles() ?: return
        children.forEach { child ->
            if (child.name == tdlibCacheRootName) {
                return@forEach
            }
            collectFilesRecursively(child, destination)
        }
    }

    private fun clearAppInternalTempFiles() {
        val children = context.filesDir.listFiles() ?: return
        children.forEach { child ->
            if (appTempFilesDirPrefixes.any(child.name::startsWith)) {
                child.deleteRecursively()
            }
        }
    }

    private fun collectInternalTempFiles(directory: File, destination: MutableList<File>) {
        val children = directory.listFiles() ?: return
        children.forEach { child ->
            if (appTempFilesDirPrefixes.any(child.name::startsWith)) {
                collectFilesRecursively(child, destination)
            }
        }
    }

    private fun collectFilesRecursively(file: File, destination: MutableList<File>) {
        if (!file.exists()) return
        if (file.isFile) {
            destination += file
            return
        }
        file.listFiles()?.forEach { child ->
            collectFilesRecursively(child, destination)
        }
    }
}