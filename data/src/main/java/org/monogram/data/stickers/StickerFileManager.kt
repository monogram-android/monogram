package org.monogram.data.stickers

import android.util.Log
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.monogram.core.DispatcherProvider
import org.monogram.core.ScopeProvider
import org.monogram.data.core.coRunCatching
import org.monogram.data.datasource.cache.StickerLocalDataSource
import org.monogram.data.infra.FileDownloadQueue
import org.monogram.data.infra.FileUpdateHandler
import org.monogram.domain.models.StickerModel
import org.monogram.domain.models.StickerSetModel
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

class StickerFileManager(
    private val localDataSource: StickerLocalDataSource,
    private val fileQueue: FileDownloadQueue,
    private val fileUpdateHandler: FileUpdateHandler,
    private val dispatchers: DispatcherProvider,
    scopeProvider: ScopeProvider
) {
    private val scope = scopeProvider.appScope

    private val tgsCache = mutableMapOf<String, String>()
    private val filePathsCache = ConcurrentHashMap<Long, String>()

    fun getStickerFile(fileId: Long): Flow<String?> = flow {
        resolveAvailablePath(fileId)?.let { path ->
            emit(path)
            return@flow
        }

        enqueueDownload(fileId, STICKER_DOWNLOAD_PRIORITY)

        val firstPath = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
            fileUpdateHandler.fileDownloadCompleted
                .filter { it.first == fileId }
                .mapNotNull { (_, path) -> path.takeIf(::isPathValid) }
                .first()
        }

        val resultPath = firstPath ?: resolveAvailablePath(fileId)
        if (!resultPath.isNullOrEmpty()) {
            filePathsCache[fileId] = resultPath
            localDataSource.insertPath(fileId, resultPath)
            emit(resultPath)
        } else {
            enqueueDownload(fileId, STICKER_DOWNLOAD_PRIORITY)
        }
    }

    fun prefetchStickers(stickers: List<StickerModel>) {
        scope.launch(dispatchers.default) {
            stickers.take(PREFETCH_COUNT).forEach { sticker ->
                if (!isStickerFileAvailable(sticker.id)) {
                    enqueueDownload(sticker.id, PREFETCH_PRIORITY)
                }
            }
        }
    }

    suspend fun verifyStickerSet(set: StickerSetModel) {
        val missing = mutableListOf<Long>()
        for (sticker in set.stickers) {
            if (!isStickerFileAvailable(sticker.id)) {
                missing += sticker.id
            }
        }

        if (missing.isEmpty()) return

        Log.d(TAG, "verifyStickerSet(${set.id}): missing ${missing.size}/${set.stickers.size}")
        missing.forEach { stickerId ->
            enqueueDownload(stickerId, VERIFY_SET_PRIORITY)
        }
    }

    suspend fun verifyInstalledStickerSets(sets: List<StickerSetModel>) {
        var requeued = 0

        for (set in sets) {
            for (sticker in set.stickers) {
                if (requeued >= MAX_VERIFY_PER_PASS) break
                if (isStickerFileAvailable(sticker.id)) continue

                enqueueDownload(sticker.id, VERIFY_PASS_PRIORITY)
                requeued++
            }
            if (requeued >= MAX_VERIFY_PER_PASS) break
        }

        if (requeued > 0) {
            Log.d(TAG, "verifyInstalledStickerSets: re-enqueued $requeued stickers")
        }
    }

    suspend fun getTgsJson(path: String): String? = withContext(dispatchers.io) {
        tgsCache[path]?.let { return@withContext it }
        coRunCatching {
            val file = File(path)
            if (!file.exists() || file.length() == 0L) return@withContext null

            GZIPInputStream(FileInputStream(file))
                .bufferedReader()
                .use { it.readText() }
                .also { tgsCache[path] = it }
        }.getOrNull()
    }

    fun clearCache() {
        tgsCache.clear()
        filePathsCache.clear()
        scope.launch {
            localDataSource.clearPaths()
        }
    }

    private suspend fun isStickerFileAvailable(stickerId: Long): Boolean {
        return resolveAvailablePath(stickerId) != null
    }

    private suspend fun resolveAvailablePath(fileId: Long): String? {
        filePathsCache[fileId]?.let { path ->
            if (isPathValid(path)) {
                return path
            }
            filePathsCache.remove(fileId)
            localDataSource.deletePath(fileId)
        }

        val dbPath = localDataSource.getPath(fileId)
        if (!dbPath.isNullOrEmpty()) {
            if (isPathValid(dbPath)) {
                filePathsCache[fileId] = dbPath
                return dbPath
            }
            localDataSource.deletePath(fileId)
        }

        val completedPath = fileUpdateHandler.fileDownloadCompleted
            .replayCache
            .firstOrNull { it.first == fileId && isPathValid(it.second) }
            ?.second

        if (!completedPath.isNullOrEmpty()) {
            filePathsCache[fileId] = completedPath
            localDataSource.insertPath(fileId, completedPath)
            return completedPath
        }

        return null
    }

    private fun enqueueDownload(fileId: Long, priority: Int) {
        fileQueue.enqueue(fileId.toInt(), priority, FileDownloadQueue.DownloadType.STICKER)
    }

    private fun isPathValid(path: String): Boolean {
        return path.isNotEmpty() && File(path).exists()
    }

    companion object {
        private const val TAG = "StickerFileManager"
        private const val DOWNLOAD_TIMEOUT_MS = 90_000L
        private const val STICKER_DOWNLOAD_PRIORITY = 32
        private const val PREFETCH_PRIORITY = 16
        private const val VERIFY_SET_PRIORITY = 32
        private const val VERIFY_PASS_PRIORITY = 8
        private const val PREFETCH_COUNT = 20
        private const val MAX_VERIFY_PER_PASS = 20
    }
}