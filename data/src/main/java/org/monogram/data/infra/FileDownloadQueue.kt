package org.monogram.data.infra

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import org.monogram.core.DispatcherProvider
import org.monogram.core.ScopeProvider
import org.monogram.data.chats.ChatCache
import org.monogram.data.gateway.TelegramGateway
import java.util.concurrent.ConcurrentHashMap
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
        val createdAt: Long = System.currentTimeMillis(),
        val availableAt: Long = System.currentTimeMillis(),
        val isManual: Boolean = false,
        val retryCount: Int = 0
    ) : Comparable<DownloadRequest> {
        override fun compareTo(other: DownloadRequest): Int {
            if (this.isManual != other.isManual) return if (this.isManual) -1 else 1
            val p = other.priority.compareTo(priority)
            if (p != 0) return p
            val a = availableAt.compareTo(other.availableAt)
            return if (a != 0) a else createdAt.compareTo(other.createdAt)
        }
    }

    private val stateMutex = Mutex()
    private val pendingRequests = ConcurrentHashMap<Int, DownloadRequest>()
    private val activeRequests = ConcurrentHashMap<Int, DownloadRequest>()
    private val failedRequests = ConcurrentHashMap<Int, DownloadRequest>()

    private val fileDownloadTypes = ConcurrentHashMap<Int, DownloadType>()
    private val manualDownloadIds = ConcurrentHashMap.newKeySet<Int>()
    private val downloadWaiters = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
    private val uploadWaiters = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()

    private val openChatIds = ConcurrentHashMap.newKeySet<Long>()
    private val visibleMessageIds = ConcurrentHashMap<Long, Set<Long>>()
    private val nearbyMessageIds = ConcurrentHashMap<Long, Set<Long>>()

    @Volatile
    private var activeChatId: Long = 0L
    @Volatile
    private var lastTaskStartAt: Long = 0L

    private val startGapMs = 160L
    private val maxTotalParallelDownloads = 5
    private val maxVideoParallelDownloads = 2
    private val maxGifParallelDownloads = 2
    private val maxDefaultParallelDownloads = 3

    private val trigger = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.appScope.launch(dispatcherProvider.default) {
            while (isActive) {
                trigger.receive()
                dispatchTasks()
            }
        }

        scope.appScope.launch(dispatcherProvider.default) {
            while (isActive) {
                delay(TimeUnit.MINUTES.toMillis(1))
                retryFailedStickers()
            }
        }
    }

    private suspend fun dispatchTasks() {
        val tasksToStart = mutableListOf<DownloadRequest>()
        val now = System.currentTimeMillis()

        stateMutex.withLock {
            var vCount =
                activeRequests.values.count { it.type == DownloadType.VIDEO || it.type == DownloadType.VIDEO_NOTE }
            var gCount = activeRequests.values.count { it.type == DownloadType.GIF }
            var dCount =
                activeRequests.values.count { it.type == DownloadType.DEFAULT || it.type == DownloadType.STICKER }
            var totalCount = activeRequests.size

            val candidates = pendingRequests.values
                .filter { it.availableAt <= now }
                .sorted()

            for (req in candidates) {
                if (totalCount >= maxTotalParallelDownloads) break
                var canStart = false
                when (req.type) {
                    DownloadType.VIDEO, DownloadType.VIDEO_NOTE -> if (vCount < maxVideoParallelDownloads) {
                        vCount++; canStart = true
                    }

                    DownloadType.GIF -> if (gCount < maxGifParallelDownloads) {
                        gCount++; canStart = true
                    }

                    DownloadType.DEFAULT, DownloadType.STICKER -> if (dCount < maxDefaultParallelDownloads) {
                        dCount++; canStart = true
                    }
                }

                if (canStart) {
                    totalCount++
                    pendingRequests.remove(req.fileId)
                    activeRequests[req.fileId] = req
                    tasksToStart.add(req)
                }
            }
        }

        for (task in tasksToStart) {
            throttleTaskStart()
            scope.appScope.launch(dispatcherProvider.io) {
                processDownload(task)
            }
        }
    }

    private suspend fun throttleTaskStart() {
        val now = System.currentTimeMillis()
        val waitMs = (lastTaskStartAt + startGapMs - now).coerceAtLeast(0L)
        if (waitMs > 0) delay(waitMs)
        lastTaskStartAt = System.currentTimeMillis()
    }

    private suspend fun processDownload(req: DownloadRequest) {
        val fileId = req.fileId

        if (!isStillRelevant(fileId)) {
            finishTask(fileId)
            return
        }

        val deferred = downloadWaiters.getOrPut(fileId) { CompletableDeferred() }

        try {
            var file = withTimeoutOrNull(30000) { gateway.execute(TdApi.GetFile(fileId)) }
            if (file == null) {
                handleDownloadFailure(req)
                return
            }
            cache.fileCache[fileId] = file

            if (file.local.isDownloadingCompleted) {
                deferred.complete(Unit)
                return
            }

            val downloadCommand = withTimeoutOrNull(30000) {
                gateway.execute(TdApi.DownloadFile(fileId, req.priority, req.offset, req.limit, req.synchronous))
            }
            if (downloadCommand == null) {
                handleDownloadFailure(req)
                return
            }

            file = withTimeoutOrNull(30000) { gateway.execute(TdApi.GetFile(fileId)) }
            if (file == null) {
                handleDownloadFailure(req)
                return
            }
            cache.fileCache[fileId] = file

            if (file.local.isDownloadingCompleted) {
                deferred.complete(Unit)
                return
            }

            if (!file.local.isDownloadingActive) {
                // Retry once if it failed to start immediately
                withTimeoutOrNull(30000) {
                    gateway.execute(TdApi.DownloadFile(fileId, req.priority, req.offset, req.limit, req.synchronous))
                }
                file = withTimeoutOrNull(30000) { gateway.execute(TdApi.GetFile(fileId)) }
                if (file == null) {
                    handleDownloadFailure(req)
                    return
                }
                cache.fileCache[fileId] = file

                if (!file.local.isDownloadingActive && !file.local.isDownloadingCompleted) {
                    handleDownloadFailure(req)
                    return
                }
            }

            val timeoutMinutes = when (req.type) {
                DownloadType.VIDEO -> 15L
                DownloadType.STICKER, DownloadType.VIDEO_NOTE -> 5L
                else -> 120L
            }

            val result = withTimeoutOrNull(TimeUnit.MINUTES.toMillis(timeoutMinutes)) {
                deferred.await()
            }

            if (result == null) {
                // Timeout reached
                handleDownloadFailure(req)
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.e("FileDownloadQueue", "Download failed for $fileId: ${e.message}")
                handleDownloadFailure(req)
            }
        } finally {
            finishTask(fileId)

            try {
                val finalFile = withTimeoutOrNull(10000) { gateway.execute(TdApi.GetFile(fileId)) }
                if (finalFile != null) {
                    cache.fileCache[fileId] = finalFile
                    if (finalFile.local.isDownloadingCompleted) {
                        notifyDownloadComplete(fileId)
                    } else if (!finalFile.local.isDownloadingActive && !hasPendingOrActiveRequest(fileId)) {
                        notifyDownloadCancelled(fileId)
                    }
                } else if (!hasPendingOrActiveRequest(fileId)) {
                    notifyDownloadCancelled(fileId)
                }
            } catch (_: Exception) {
                if (!hasPendingOrActiveRequest(fileId)) {
                    notifyDownloadCancelled(fileId)
                }
            }
        }
    }

    private suspend fun hasPendingOrActiveRequest(fileId: Int): Boolean {
        return stateMutex.withLock {
            pendingRequests.containsKey(fileId) || activeRequests.containsKey(fileId)
        }
    }

    private suspend fun handleDownloadFailure(req: DownloadRequest) {
        if (req.retryCount < 3) {
            val backoffMs = (req.retryCount + 1) * 5000L
            val nextReq = req.copy(
                retryCount = req.retryCount + 1,
                availableAt = System.currentTimeMillis() + backoffMs
            )
            stateMutex.withLock {
                pendingRequests[req.fileId] = nextReq
            }
            trigger.trySend(Unit)
            scope.appScope.launch(dispatcherProvider.default) {
                delay(backoffMs)
                trigger.trySend(Unit)
            }
        } else {
            failedRequests[req.fileId] = req
            downloadWaiters.remove(req.fileId)?.cancel()
        }
    }

    private fun retryFailedStickers() {
        val toRetry = failedRequests.filter { it.value.type == DownloadType.STICKER }
        toRetry.forEach { (id, req) ->
            failedRequests.remove(id)
            enqueue(id, req.priority, req.type, req.offset, req.limit, req.synchronous)
        }
    }

    private suspend fun finishTask(fileId: Int) {
        stateMutex.withLock {
            activeRequests.remove(fileId)
        }
        trigger.trySend(Unit)
    }

    fun updateFileCache(file: TdApi.File) {
        val oldFile = cache.fileCache[file.id]
        cache.fileCache[file.id] = file

        if (file.local.isDownloadingCompleted) {
            manualDownloadIds.remove(file.id)
            failedRequests.remove(file.id)
            scope.appScope.launch {
                stateMutex.withLock { pendingRequests.remove(file.id) }
            }
            notifyDownloadComplete(file.id)
        } else if (oldFile?.local?.isDownloadingActive == true && !file.local.isDownloadingActive) {
            manualDownloadIds.remove(file.id)
        }

        if (file.remote.isUploadingCompleted) {
            notifyUploadComplete(file.id)
        }
    }

    fun isFileQueued(fileId: Int) = pendingRequests.containsKey(fileId) || activeRequests.containsKey(fileId)

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
        scope.appScope.launch(dispatcherProvider.default) {
            val isManualRequest = priority >= 32
            if (isManualRequest) manualDownloadIds.add(fileId)

            if (registry.getMessages(fileId).isEmpty()) registry.standaloneFileIds.add(fileId)
            fileDownloadTypes[fileId] = type

            val cached = cache.fileCache[fileId]
            if (cached?.local?.isDownloadingCompleted == true) {
                notifyDownloadComplete(fileId)
                manualDownloadIds.remove(fileId)
                return@launch
            }

            val finalOffset = if (type == DownloadType.STICKER || type == DownloadType.VIDEO_NOTE) 0L else offset
            val finalLimit = if (type == DownloadType.STICKER || type == DownloadType.VIDEO_NOTE) 0L else limit

            val prio = calculatePriority(fileId).coerceAtLeast(priority)
            val isManual = manualDownloadIds.contains(fileId)
            val req = DownloadRequest(
                fileId = fileId,
                priority = prio,
                type = type,
                offset = finalOffset,
                limit = finalLimit,
                synchronous = synchronous,
                createdAt = System.currentTimeMillis(),
                availableAt = System.currentTimeMillis(),
                isManual = isManual
            )

            stateMutex.withLock {
                val active = activeRequests[fileId]
                if (active != null) {
                    val merged = mergeRequests(active, req)
                    if (merged != active) {
                        activeRequests[fileId] = merged
                        scope.appScope.launch(dispatcherProvider.io) {
                            try {
                                gateway.execute(
                                    TdApi.DownloadFile(
                                        fileId,
                                        merged.priority,
                                        merged.offset,
                                        merged.limit,
                                        merged.synchronous
                                    )
                                )
                            } catch (_: Exception) {
                            }
                        }
                    }
                    return@launch
                }

                val pending = pendingRequests[fileId]
                if (pending != null) {
                    pendingRequests[fileId] = mergeRequests(pending, req)
                } else {
                    pendingRequests[fileId] = req
                }
            }
            trigger.trySend(Unit)
        }
    }

    fun cancelDownload(fileId: Int, force: Boolean = false) {
        if (!force && manualDownloadIds.contains(fileId)) return

        scope.appScope.launch(dispatcherProvider.io) {
            try {
                gateway.execute(TdApi.CancelDownloadFile(fileId, true))
            } catch (_: Exception) {
            }

            stateMutex.withLock {
                pendingRequests.remove(fileId)
                activeRequests.remove(fileId)
                failedRequests.remove(fileId)
            }
            notifyDownloadCancelled(fileId)
        }
    }

    fun waitForDownload(fileId: Int): CompletableDeferred<Unit> {
        val cached = cache.fileCache[fileId]
        if (cached?.local?.isDownloadingCompleted == true) return CompletableDeferred(Unit)
        return downloadWaiters.getOrPut(fileId) { CompletableDeferred() }
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

    private fun mergeRequests(old: DownloadRequest, new: DownloadRequest): DownloadRequest {
        val p = maxOf(old.priority, new.priority)
        val isManual = old.isManual || new.isManual

        val curEnd = if (old.limit == 0L) Long.MAX_VALUE else old.offset + old.limit
        val newEnd = if (new.limit == 0L) Long.MAX_VALUE else new.offset + new.limit
        val start = minOf(old.offset, new.offset)
        val end = if (curEnd == Long.MAX_VALUE || newEnd == Long.MAX_VALUE) Long.MAX_VALUE else maxOf(curEnd, newEnd)
        val limit = if (end == Long.MAX_VALUE) 0L else end - start

        return old.copy(
            priority = p,
            isManual = isManual,
            offset = start,
            limit = limit,
            availableAt = minOf(old.availableAt, new.availableAt)
        )
    }

    private fun cancelIrrelevantDownloads() {
        scope.appScope.launch(dispatcherProvider.default) {
            val toCancel = mutableListOf<Int>()

            for ((fileId, _) in pendingRequests) {
                if (!isStillRelevant(fileId)) toCancel.add(fileId)
            }
            for ((fileId, _) in activeRequests) {
                if (!isStillRelevant(fileId)) toCancel.add(fileId)
            }

            toCancel.forEach { fileId -> cancelDownload(fileId, force = false) }
        }
    }
}