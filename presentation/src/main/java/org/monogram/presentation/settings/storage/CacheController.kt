package org.monogram.presentation.settings.storage

import android.content.Context
import coil3.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.monogram.presentation.core.media.ExoPlayerCache
import java.io.File

data class AppTempCacheUsage(
    val size: Long,
    val fileCount: Int
) {
    val isEmpty: Boolean get() = size <= 0L || fileCount <= 0
}

internal data class AppCacheTrimResult(
    val sizeBefore: Long,
    val sizeAfter: Long,
    val deletedFileCount: Int
) {
    val freedSize: Long get() = (sizeBefore - sizeAfter).coerceAtLeast(0L)
}

class CacheController(val context: Context, val exoPlayerCache: ExoPlayerCache) {
    private val appTempFilesDirPrefixes = listOf("CIRCLE_FULL_")
    private val trimMutex = Mutex()

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

    internal suspend fun enforceCacheLimit(maxSizeBytes: Long): AppCacheTrimResult =
        trimMutex.withLock {
            withContext(Dispatchers.IO) {
                val roots = cacheScanRoots()
                val sizeBefore = roots.sumOf(::cacheFilesSize)
                if (maxSizeBytes < 0L || sizeBefore <= maxSizeBytes) {
                    return@withContext AppCacheTrimResult(sizeBefore, sizeBefore, 0)
                }

                clearExo()
                clearImageLoader()
                trimCacheFiles(
                    roots = cacheScanRoots(),
                    targetSizeBytes = maxSizeBytes - maxSizeBytes / 10,
                    protectedTopLevelNames = emptySet(),
                    minFileAgeMillis = AUTO_TRIM_FILE_IMMUNITY_MILLIS
                ).copy(sizeBefore = sizeBefore)
            }
        }

    private fun clearAppTempFiles() {
        clearDirectoryChildren(context.cacheDir)
        context.externalCacheDir?.let(::clearDirectoryChildren)
    }

    private fun clearDirectoryChildren(directory: File) {
        val children = directory.listFiles() ?: return
        children.forEach(File::deleteRecursively)
    }

    private fun collectCacheTempFiles(directory: File, destination: MutableList<File>) {
        val children = directory.listFiles() ?: return
        children.forEach { child -> collectFilesRecursively(child, destination) }
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

    private fun cacheScanRoots(): List<File> = buildList {
        add(context.cacheDir)
        context.externalCacheDir?.let(::add)
        context.filesDir.listFiles()
            ?.filter { child -> appTempFilesDirPrefixes.any(child.name::startsWith) }
            ?.let(::addAll)
    }

    companion object {
        private const val AUTO_TRIM_FILE_IMMUNITY_MILLIS = 60L * 60L * 1000L
    }
}

internal fun trimCacheFiles(
    roots: List<File>,
    targetSizeBytes: Long,
    protectedTopLevelNames: Set<String>,
    minFileAgeMillis: Long,
    nowMillis: Long = System.currentTimeMillis()
): AppCacheTrimResult {
    val files = roots
        .distinctBy { it.absolutePath }
        .flatMap { root -> collectCacheFiles(root, protectedTopLevelNames) }
    val sizeBefore = files.sumOf(File::length)
    var sizeAfter = sizeBefore
    var deletedFileCount = 0
    val oldestAllowedModifiedTime = nowMillis - minFileAgeMillis.coerceAtLeast(0L)

    files.asSequence()
        .filter { file -> file.lastModified() <= oldestAllowedModifiedTime }
        .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.absolutePath })
        .forEach { file ->
            if (sizeAfter <= targetSizeBytes.coerceAtLeast(0L)) return@forEach
            val fileSize = file.length()
            if (file.delete()) {
                sizeAfter = (sizeAfter - fileSize).coerceAtLeast(0L)
                deletedFileCount++
            }
        }

    roots.forEach { root -> deleteEmptyDirectories(root, protectedTopLevelNames) }
    return AppCacheTrimResult(sizeBefore, sizeAfter, deletedFileCount)
}

private fun cacheFilesSize(root: File): Long =
    collectCacheFiles(root, emptySet()).sumOf(File::length)

private fun collectCacheFiles(root: File, protectedTopLevelNames: Set<String>): List<File> {
    if (!root.exists()) return emptyList()
    if (root.isFile) return listOf(root)

    return buildList {
        root.listFiles()?.forEach { child ->
            if (child.name !in protectedTopLevelNames) {
                collectAllFiles(child, this)
            }
        }
    }
}

private fun collectAllFiles(file: File, destination: MutableList<File>) {
    if (file.isFile) {
        destination += file
    } else {
        file.listFiles()?.forEach { child -> collectAllFiles(child, destination) }
    }
}

private fun deleteEmptyDirectories(root: File, protectedTopLevelNames: Set<String>) {
    if (!root.isDirectory) return
    root.listFiles()
        ?.filter { child -> child.isDirectory && child.name !in protectedTopLevelNames }
        ?.forEach { child ->
            deleteEmptyDirectories(child, emptySet())
            if (child.listFiles()?.isEmpty() == true) {
                child.delete()
            }
        }
}
