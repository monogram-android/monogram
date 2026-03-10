package org.monogram.data.infra

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.drinkless.tdlib.TdApi
import org.monogram.core.DispatcherProvider
import org.monogram.core.ScopeProvider
import org.monogram.data.chats.ChatCache
import org.monogram.data.gateway.TelegramGateway
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.TimeUnit

class FileDownloadQueue(
    private val gateway: TelegramGateway,
    val registry: FileMessageRegistry,
    private val cache: ChatCache,
    private val scope: ScopeProvider,
    private val dispatcherProvider: DispatcherProvider
) {
    enum class DownloadType { VIDEO, GIF, STICKER, VIDEO_NOTE, DEFAULT }

    private data class DownloadRequest(
        val fileId: Int,
        val priority: Int,
        val type: DownloadType,
        val offset: Long = 0,
        val limit: Long = 0,
        val synchronous: Boolean = false,
        val timestamp: Long = System.currentTimeMillis(),
        val isManual: Boolean = false
    ) : Comparable<DownloadRequest> {
        override fun compareTo(other: DownloadRequest): Int {
            if (this.isManual != other.isManual) return if (this.isManual) -1 else 1
            val p = other.priority.compareTo(priority)
            return if (p != 0) p else timestamp.compareTo(other.timestamp)
        }
    }

    private val fileDownloadTypes = ConcurrentHashMap<Int, DownloadType>()
    private val activeDownloads = ConcurrentHashMap<Int, DownloadRequest>()
    private val queuedRequests = ConcurrentHashMap<Int, DownloadRequest>()
    private val manualDownloadIds = ConcurrentHashMap.newKeySet<Int>()
    private val downloadWaiters = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
    private val uploadWaiters = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()

    private val openChatIds = ConcurrentHashMap.newKeySet<Long>()
    private val visibleMessageIds = ConcurrentHashMap<Long, Set<Long>>()
    private val nearbyMessageIds = ConcurrentHashMap<Long, Set<Long>>()
    @Volatile
    private var activeChatId: Long = 0L

    private val videoQueue = PriorityBlockingQueue<DownloadRequest>()
    private val gifQueue = PriorityBlockingQueue<DownloadRequest>()
    private val defaultQueue = PriorityBlockingQueue<DownloadRequest>()

    private val videoChannel = Channel<Unit>(Channel.UNLIMITED)
    private val gifChannel = Channel<Unit>(Channel.UNLIMITED)
    private val defaultChannel = Channel<Unit>(Channel.UNLIMITED)

    init {
        repeat(4) { startWorker(videoQueue, videoChannel) }
        repeat(4) { startWorker(gifQueue, gifChannel) }
        repeat(8) { startWorker(defaultQueue, defaultChannel) }
    }

    fun updateFileCache(file: TdApi.File) {
        val oldFile = cache.fileCache[file.id]
        cache.fileCache[file.id] = file

        if (file.local.isDownloadingCompleted) {
            manualDownloadIds.remove(file.id)
            queuedRequests.remove(file.id)
            activeDownloads.remove(file.id)
            notifyDownloadComplete(file.id)
        } else if (oldFile?.local?.isDownloadingActive == true && !file.local.isDownloadingActive) {
            // Download stopped but not completed (failed or cancelled)
            notifyDownloadCancelled(file.id)
            activeDownloads.remove(file.id)
            manualDownloadIds.remove(file.id)
        }

        if (file.remote.isUploadingCompleted) {
            notifyUploadComplete(file.id)
        } else if (oldFile?.remote?.isUploadingActive == true && !file.remote.isUploadingActive) {
            notifyUploadCancelled(file.id)
        }
    }

    fun isFileQueued(fileId: Int) = queuedRequests.containsKey(fileId) || activeDownloads.containsKey(fileId)

    fun setChatOpened(chatId: Long) {
        openChatIds.add(chatId)
        activeChatId = chatId
    }

    fun setChatClosed(chatId: Long) {
        openChatIds.remove(chatId)
        visibleMessageIds.remove(chatId)
        nearbyMessageIds.remove(chatId)
        if (activeChatId == chatId) activeChatId = 0L
        registry.unregisterChat(chatId)
        cancelIrrelevantDownloads()
    }

    fun updateVisibleRange(chatId: Long, visible: List<Long>, nearby: List<Long>) {
        visibleMessageIds[chatId] = visible.toSet()
        nearbyMessageIds[chatId] = nearby.toSet()
        activeChatId = chatId
        scope.appScope.launch(dispatcherProvider.default) {
            cancelIrrelevantDownloads()
            (visible + nearby).forEach { messageId ->
                registry.getFileIdsForMessage(chatId, messageId).forEach { fileId ->
                    fileDownloadTypes[fileId]?.let { type ->
                        enqueue(fileId, calculatePriority(fileId), type)
                    }
                }
            }
        }
    }

    fun enqueue(
        fileId: Int,
        priority: Int = 1,
        type: DownloadType = DownloadType.DEFAULT,
        offset: Long = 0,
        limit: Long = 0,
        synchronous: Boolean = false
    ) {
        val isManualRequest = priority >= 32
        if (isManualRequest) manualDownloadIds.add(fileId)

        if (registry.getMessages(fileId).isEmpty()) registry.standaloneFileIds.add(fileId)
        fileDownloadTypes[fileId] = type

        val cached = cache.fileCache[fileId]
        if (cached?.local?.isDownloadingCompleted == true) {
            notifyDownloadComplete(fileId)
            manualDownloadIds.remove(fileId)
            return
        }

        val existing = activeDownloads[fileId] ?: queuedRequests[fileId]
        if (existing != null) {
            if (updateExisting(existing, priority, offset, limit, isManualRequest)) return
        }

        val prio = calculatePriority(fileId).coerceAtLeast(priority)
        val isManual = manualDownloadIds.contains(fileId)
        val req = DownloadRequest(fileId, prio, type, offset, limit, synchronous, isManual = isManual)
        queuedRequests[fileId] = req
        
        when (type) {
            DownloadType.VIDEO, DownloadType.VIDEO_NOTE -> {
                videoQueue.offer(req)
                videoChannel.trySend(Unit)
            }
            DownloadType.GIF -> {
                gifQueue.offer(req)
                gifChannel.trySend(Unit)
            }
            DownloadType.DEFAULT, DownloadType.STICKER -> {
                defaultQueue.offer(req)
                defaultChannel.trySend(Unit)
            }
        }
    }

    fun cancelDownload(fileId: Int) {
        if (manualDownloadIds.contains(fileId)) return

        scope.appScope.launch {
            try {
                gateway.execute(TdApi.CancelDownloadFile(fileId, true))
            } catch (_: Exception) {
            }
        }

        queuedRequests.remove(fileId)

        activeDownloads.remove(fileId)
        notifyDownloadCancelled(fileId)
    }

    fun waitForUpload(fileId: Int): CompletableDeferred<Unit> {
        if (cache.fileCache[fileId]?.remote?.isUploadingCompleted == true) return CompletableDeferred(Unit)
        return uploadWaiters.getOrPut(fileId) { CompletableDeferred() }
    }

    fun notifyDownloadComplete(fileId: Int) {
        downloadWaiters.remove(fileId)?.complete(Unit)
    }

    fun notifyDownloadCancelled(fileId: Int) {
        downloadWaiters.remove(fileId)?.cancel()
    }

    fun notifyUploadComplete(fileId: Int) {
        uploadWaiters.remove(fileId)?.complete(Unit)
    }

    fun notifyUploadCancelled(fileId: Int) {
        uploadWaiters.remove(fileId)?.cancel()
    }

    private fun startWorker(queue: PriorityBlockingQueue<DownloadRequest>, ch: Channel<Unit>) {
        scope.appScope.launch(dispatcherProvider.io) {
            for (trigger in ch) {
                try {
                    val req = queue.poll() ?: continue
                    if (queuedRequests[req.fileId] !== req) continue
                    
                    if (!isStillRelevant(req.fileId)) {
                        queuedRequests.remove(req.fileId)
                        continue
                    }
                    processDownload(req)
                } catch (e: Exception) {
                    if (e is CancellationException && !scope.appScope.isActive) throw e
                    Log.e("FileDownloadQueue", "Worker error", e)
                }
            }
        }
    }

    private suspend fun processDownload(req: DownloadRequest) {
        val fileId = req.fileId
        if (queuedRequests[fileId] === req) {
            queuedRequests.remove(fileId)
        } else {
            return
        }
        
        if (!isStillRelevant(fileId)) return
        if (activeDownloads.putIfAbsent(fileId, req) != null) return

        val deferred = CompletableDeferred<Unit>()
        downloadWaiters[fileId] = deferred

        try {
            var file = gateway.execute(TdApi.GetFile(fileId))
            cache.fileCache[fileId] = file

            if (file.local.isDownloadingCompleted) {
                deferred.complete(Unit)
                return
            }

            gateway.execute(TdApi.DownloadFile(fileId, req.priority, req.offset, req.limit, req.synchronous))

            file = gateway.execute(TdApi.GetFile(fileId))
            cache.fileCache[fileId] = file
            if (file.local.isDownloadingCompleted) {
                deferred.complete(Unit)
                return
            }

            if (!file.local.isDownloadingActive) {
                // Retry once if it failed to start immediately
                gateway.execute(TdApi.DownloadFile(fileId, req.priority, req.offset, req.limit, req.synchronous))
                file = gateway.execute(TdApi.GetFile(fileId))
                cache.fileCache[fileId] = file

                if (!file.local.isDownloadingActive && !file.local.isDownloadingCompleted) {
                    deferred.cancel()
                    return
                }
            }

            val timeout = when (req.type) {
                DownloadType.VIDEO -> 15L
                DownloadType.STICKER, DownloadType.VIDEO_NOTE -> 2L
                else -> 120L
            }
            withTimeoutOrNull(TimeUnit.MINUTES.toMillis(timeout)) {
                deferred.await()
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.e("FileDownloadQueue", "Download failed for $fileId: ${e.message}")
            }
        } finally {
            val d = downloadWaiters.remove(fileId)
            activeDownloads.remove(fileId)

            try {
                val finalFile = gateway.execute(TdApi.GetFile(fileId))
                cache.fileCache[fileId] = finalFile

                if (finalFile.local.isDownloadingCompleted) {
                    d?.complete(Unit)
                } else {
                    if (!finalFile.local.isDownloadingActive) {
                        d?.cancel()
                    }
                }
            } catch (_: Exception) {
                d?.cancel()
            }
        }
    }

    private fun isStillRelevant(fileId: Int): Boolean {
        if (manualDownloadIds.contains(fileId)) return true
        if (registry.standaloneFileIds.contains(fileId)) return true
        val type = fileDownloadTypes[fileId]
        if (type == DownloadType.STICKER || type == DownloadType.VIDEO_NOTE) return true
        return registry.getMessages(fileId).any { (chatId, msgId) ->
            openChatIds.contains(chatId) &&
                    (visibleMessageIds[chatId]?.contains(msgId) == true ||
                            nearbyMessageIds[chatId]?.contains(msgId) == true)
        }
    }

    private fun calculatePriority(fileId: Int): Int {
        if (manualDownloadIds.contains(fileId)) return 32
        
        val messages = registry.getMessages(fileId)
        var max = 1
        val type = fileDownloadTypes[fileId]

        if (type == DownloadType.STICKER || type == DownloadType.VIDEO_NOTE) {
            max = 32
        }

        messages.forEach { (chatId, msgId) ->
            val isVisible = visibleMessageIds[chatId]?.contains(msgId) == true
            val isNearby = nearbyMessageIds[chatId]?.contains(msgId) == true

            var p = 1
            if (isVisible) {
                p = if (chatId == activeChatId) 32 else 24
            } else if (isNearby) {
                p = if (chatId == activeChatId) 16 else 8
            }

            max = maxOf(max, p)
        }
        return max
    }

    private fun updateExisting(
        req: DownloadRequest,
        priority: Int,
        offset: Long,
        limit: Long,
        isManual: Boolean
    ): Boolean {
        val fileId = req.fileId

        if (updateActive(fileId, priority, offset, limit, isManual)) return true

        val res = queuedRequests.compute(fileId) { _, currentReq ->
            if (currentReq == null) return@compute null

            val (p, o, l) = mergeParams(currentReq, priority, offset, limit)
            val manualChanged = isManual && !currentReq.isManual

            if (currentReq.priority != p || currentReq.offset != o || currentReq.limit != l || manualChanged) {
                val newReq = currentReq.copy(
                    priority = p,
                    offset = o,
                    limit = l,
                    isManual = if (manualChanged) true else currentReq.isManual
                )

                val (queue, channel) = when (newReq.type) {
                    DownloadType.VIDEO, DownloadType.VIDEO_NOTE -> videoQueue to videoChannel
                    DownloadType.GIF -> gifQueue to gifChannel
                    else -> defaultQueue to defaultChannel
                }

                queue.offer(newReq)
                channel.trySend(Unit)
                newReq
            } else {
                currentReq
            }
        }

        if (res != null) return true

        if (updateActive(fileId, priority, offset, limit, isManual)) return true

        return false
    }

    private fun updateActive(fileId: Int, priority: Int, offset: Long, limit: Long, isManual: Boolean): Boolean {
        while (true) {
            val activeReq = activeDownloads[fileId] ?: return false

            val (p, o, l) = mergeParams(activeReq, priority, offset, limit)
            val manualChanged = isManual && !activeReq.isManual

            if (activeReq.priority == p && activeReq.offset == o && activeReq.limit == l && !manualChanged) {
                return true
            }

            val newReq = activeReq.copy(
                priority = p,
                offset = o,
                limit = l,
                isManual = if (manualChanged) true else activeReq.isManual
            )

            if (activeDownloads.replace(fileId, activeReq, newReq)) {
                scope.appScope.launch {
                    try {
                        val res = gateway.execute(TdApi.DownloadFile(fileId, p, o, l, newReq.synchronous))
                        if (res.local.isDownloadingCompleted) notifyDownloadComplete(fileId)
                    } catch (_: Exception) {
                    }
                }
                return true
            }
        }
    }

    private fun mergeParams(req: DownloadRequest, priority: Int, offset: Long, limit: Long): Triple<Int, Long, Long> {
        val p = maxOf(req.priority, priority)
        val curEnd = if (req.limit == 0L) Long.MAX_VALUE else req.offset + req.limit
        val newEnd = if (limit == 0L) Long.MAX_VALUE else offset + limit
        val start = minOf(req.offset, offset)
        val end = if (curEnd == Long.MAX_VALUE || newEnd == Long.MAX_VALUE) Long.MAX_VALUE else maxOf(curEnd, newEnd)
        return Triple(p, start, if (end == Long.MAX_VALUE) 0L else end - start)
    }

    private fun cancelIrrelevantDownloads() {
        (activeDownloads.keys + queuedRequests.keys).forEach { fileId ->
            if (!isStillRelevant(fileId)) cancelDownload(fileId)
        }
    }
}