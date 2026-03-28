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
import org.monogram.data.di.TdLibException
import org.monogram.data.gateway.TelegramGateway
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

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
    private val notFoundCooldownUntil = ConcurrentHashMap<Int, Long>()

    private val fileDownloadTypes = ConcurrentHashMap<Int, DownloadType>()
    private val manualDownloadIds = ConcurrentHashMap.newKeySet<Int>()
    private val suppressedAutoDownloadIds = ConcurrentHashMap.newKeySet<Int>()
    private val downloadWaiters = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
    private val uploadWaiters = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
    private val lastProgressAt = ConcurrentHashMap<Int, Long>()
    private val stalledRecoveryAt = ConcurrentHashMap<Int, Long>()

    private val openChatIds = ConcurrentHashMap.newKeySet<Long>()
    private val visibleMessageIds = ConcurrentHashMap<Long, Set<Long>>()
    private val nearbyMessageIds = ConcurrentHashMap<Long, Set<Long>>()

    @Volatile
    private var activeChatId: Long = 0L
    @Volatile
    private var lastTaskStartAt: Long = 0L

    private val startGapMs = 160L
    private val notFoundCooldownMs = TimeUnit.MINUTES.toMillis(2)
    private val maxTotalParallelDownloads = 10
    private val maxVideoParallelDownloads = 3
    private val maxGifParallelDownloads = 2
    private val maxDefaultParallelDownloads = 4
    private val maxPendingDefaultAutoDownloads = 20
    private val maxStickerParallelDownloads = 5
    private val stickerStallMs = 20_000L
    private val defaultStallMs = 35_000L
    private val stalledRecoveryCooldownMs = 12_000L

    private val trigger = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.appScope.launch(dispatcherProvider.default) {
            while (isActive) {
                trigger.receive()
                runCatching { dispatchTasks() }
                    .onFailure { Log.e("FileDownloadQueue", "dispatchTasks failed", it) }
            }
        }

        scope.appScope.launch(dispatcherProvider.default) {
            while (isActive) {
                delay(TimeUnit.MINUTES.toMillis(1))
                runCatching { retryFailedDownloads() }
                    .onFailure { Log.e("FileDownloadQueue", "retryFailedDownloads failed", it) }
            }
        }

        scope.appScope.launch(dispatcherProvider.default) {
            while (isActive) {
                delay(15_000)
                runCatching { recoverStalledDownloads() }
                    .onFailure { Log.e("FileDownloadQueue", "recoverStalledDownloads failed", it) }
            }
        }

        scope.appScope.launch(dispatcherProvider.default) {
            while (isActive) {
                delay(TimeUnit.MINUTES.toMillis(5))
                runCatching { cleanupDeadState() }
                    .onFailure { Log.e("FileDownloadQueue", "cleanupDeadState failed", it) }
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
            var dCount = activeRequests.values.count { it.type == DownloadType.DEFAULT }
            var sCount = activeRequests.values.count { it.type == DownloadType.STICKER }
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

                    DownloadType.DEFAULT -> if (dCount < maxDefaultParallelDownloads) {
                        dCount++; canStart = true
                    }

                    DownloadType.STICKER -> if (sCount < maxStickerParallelDownloads) {
                        sCount++; canStart = true
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
            val cached = cache.fileCache[fileId]
            if (cached?.local?.isDownloadingCompleted == true) {
                deferred.complete(Unit)
                return
            }

            lastProgressAt[fileId] = System.currentTimeMillis()

            Log.d(
                "DownloadDebug",
                "queue.processDownload.start: fileId=$fileId priority=${req.priority} type=${req.type} sync=${req.synchronous}"
            )

            val started = withTimeoutOrNull(30000) {
                gateway.execute(TdApi.DownloadFile(fileId, req.priority, req.offset, req.limit, req.synchronous))
            }
            if (started == null) {
                handleDownloadFailure(req)
                return
            }

            val timeoutMs = when (req.type) {
                DownloadType.VIDEO -> TimeUnit.MINUTES.toMillis(10)
                DownloadType.STICKER -> TimeUnit.SECONDS.toMillis(90)
                DownloadType.VIDEO_NOTE -> TimeUnit.MINUTES.toMillis(2)
                DownloadType.GIF -> TimeUnit.MINUTES.toMillis(3)
                DownloadType.DEFAULT -> TimeUnit.MINUTES.toMillis(3)
            }

            val completed = withTimeoutOrNull(timeoutMs) {
                deferred.await()
            }

            if (completed == null) {
                handleDownloadFailure(req)
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.e("FileDownloadQueue", "Download failed for $fileId: ${e.message}")
                val tdErrorCode = (e as? TdLibException)?.error?.code
                if (tdErrorCode == 404 && req.type == DownloadType.STICKER) {
                    handleNotFoundDownloadFailure(req)
                } else {
                    handleDownloadFailure(req, tdErrorCode)
                }
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

    private suspend fun handleDownloadFailure(req: DownloadRequest, errorCode: Int? = null) {
        val nextRetry = req.retryCount + 1
        val maxRetries = when (req.type) {
            DownloadType.VIDEO -> 4
            DownloadType.GIF -> 5
            DownloadType.STICKER, DownloadType.VIDEO_NOTE -> 6
            DownloadType.DEFAULT -> 5
        }

        if (nextRetry <= maxRetries) {
            val backoffMs = calculateBackoffMs(req, nextRetry, errorCode)
            val nextReq = req.copy(
                retryCount = nextRetry,
                availableAt = System.currentTimeMillis() + backoffMs
            )
            stateMutex.withLock {
                pendingRequests[req.fileId] = nextReq
                failedRequests.remove(req.fileId)
            }
            trigger.trySend(Unit)
            scope.appScope.launch(dispatcherProvider.default) {
                delay(backoffMs)
                trigger.trySend(Unit)
            }
        } else {
            val cooldownMs = TimeUnit.MINUTES.toMillis(5)
            failedRequests[req.fileId] = req.copy(availableAt = System.currentTimeMillis() + cooldownMs)
            downloadWaiters.remove(req.fileId)?.cancel()
        }
    }

    private suspend fun handleNotFoundDownloadFailure(req: DownloadRequest) {
        val nextRetry = req.retryCount + 1
        val maxRetries = if (req.type == DownloadType.STICKER) 10 else 4
        val cooldownMs = (notFoundCooldownMs * nextRetry.coerceAtMost(6)).coerceAtMost(TimeUnit.MINUTES.toMillis(15))
        val availableAt = System.currentTimeMillis() + cooldownMs
        notFoundCooldownUntil[req.fileId] = availableAt

        if (nextRetry <= maxRetries) {
            val nextReq = req.copy(retryCount = nextRetry, availableAt = availableAt)
            stateMutex.withLock {
                pendingRequests[req.fileId] = nextReq
                failedRequests.remove(req.fileId)
            }
            trigger.trySend(Unit)
            scope.appScope.launch(dispatcherProvider.default) {
                delay(cooldownMs)
                trigger.trySend(Unit)
            }
        } else {
            failedRequests[req.fileId] = req.copy(availableAt = availableAt)
            downloadWaiters.remove(req.fileId)?.cancel()
        }
    }

    private fun calculateBackoffMs(req: DownloadRequest, attempt: Int, errorCode: Int?): Long {
        val baseMs = when {
            errorCode == 429 -> 10_000L
            req.type == DownloadType.VIDEO -> 8_000L
            req.type == DownloadType.GIF -> 6_000L
            else -> 4_000L
        }
        val capMs = when (req.type) {
            DownloadType.VIDEO -> TimeUnit.MINUTES.toMillis(3)
            DownloadType.STICKER, DownloadType.VIDEO_NOTE -> TimeUnit.MINUTES.toMillis(2)
            else -> TimeUnit.MINUTES.toMillis(5)
        }
        val scaled = (baseMs * attempt.coerceAtMost(8)).coerceAtMost(capMs)
        val jitter = Random.nextLong(250L, 1_250L)
        return scaled + jitter
    }

    private fun retryFailedDownloads() {
        val now = System.currentTimeMillis()
        val toRetry = failedRequests.filter { it.value.availableAt <= now }
        toRetry.forEach { (id, req) ->
            failedRequests.remove(id)
            enqueue(id, req.priority, req.type, req.offset, req.limit, req.synchronous)
        }
    }

    private fun cleanupDeadState() {
        val now = System.currentTimeMillis()

        notFoundCooldownUntil.entries.removeIf { it.value <= now }
        failedRequests.entries.removeIf { now - it.value.availableAt > TimeUnit.MINUTES.toMillis(30) }

        val live = HashSet<Int>(pendingRequests.size + activeRequests.size + failedRequests.size)
        live.addAll(pendingRequests.keys)
        live.addAll(activeRequests.keys)
        live.addAll(failedRequests.keys)

        fileDownloadTypes.entries.removeIf { it.key !in live }
        lastProgressAt.entries.removeIf { !activeRequests.containsKey(it.key) }
        stalledRecoveryAt.entries.removeIf { !activeRequests.containsKey(it.key) }

        val completedStandalone = registry.standaloneFileIds.filter { fileId ->
            cache.fileCache[fileId]?.local?.isDownloadingCompleted == true
        }
        completedStandalone.forEach { registry.standaloneFileIds.remove(it) }
    }

    private fun recoverStalledDownloads() {
        val now = System.currentTimeMillis()
        activeRequests.values.forEach { req ->
            val timeoutMs = when (req.type) {
                DownloadType.STICKER -> stickerStallMs
                DownloadType.DEFAULT, DownloadType.GIF, DownloadType.VIDEO_NOTE -> defaultStallMs
                DownloadType.VIDEO -> TimeUnit.MINUTES.toMillis(2)
            }
            val lastProgress = lastProgressAt[req.fileId] ?: req.createdAt
            if (now - lastProgress >= timeoutMs) {
                val recoveredAt = stalledRecoveryAt[req.fileId] ?: 0L
                if (now - recoveredAt < stalledRecoveryCooldownMs) return@forEach
                stalledRecoveryAt[req.fileId] = now

                scope.appScope.launch(dispatcherProvider.default) {
                    val recovered = stateMutex.withLock {
                        val active = activeRequests[req.fileId] ?: return@withLock false
                        if (active.createdAt != req.createdAt || active.availableAt != req.availableAt) return@withLock false

                        activeRequests.remove(req.fileId)
                        pendingRequests[req.fileId] = mergeRequests(
                            pendingRequests[req.fileId] ?: req,
                            req.copy(
                                priority = if (req.type == DownloadType.STICKER) maxOf(req.priority, 32) else maxOf(req.priority, 16),
                                availableAt = System.currentTimeMillis() + 250L,
                                createdAt = System.currentTimeMillis()
                            )
                        )
                        true
                    }

                    if (recovered) {
                        runCatching {
                            withContext(dispatcherProvider.io) {
                                gateway.execute(TdApi.CancelDownloadFile(req.fileId, false))
                            }
                        }
                        lastProgressAt[req.fileId] = System.currentTimeMillis()
                        trigger.trySend(Unit)
                    }
                }
            }
        }
    }

    private suspend fun finishTask(fileId: Int) {
        stateMutex.withLock {
            activeRequests.remove(fileId)
        }
        stalledRecoveryAt.remove(fileId)
        trigger.trySend(Unit)
    }

    fun updateFileCache(file: TdApi.File) {
        val oldFile = cache.fileCache[file.id]
        cache.fileCache[file.id] = file
        val now = System.currentTimeMillis()
        if (file.local.downloadedSize > (oldFile?.local?.downloadedSize ?: -1)) {
            lastProgressAt[file.id] = now
        }
        if (file.local.isDownloadingActive || file.local.isDownloadingCompleted) {
            notFoundCooldownUntil.remove(file.id)
            lastProgressAt[file.id] = now
        }

        if (file.local.isDownloadingCompleted) {
            manualDownloadIds.remove(file.id)
            failedRequests.remove(file.id)
            stalledRecoveryAt.remove(file.id)
            lastProgressAt.remove(file.id)
            scope.appScope.launch {
                stateMutex.withLock { pendingRequests.remove(file.id) }
            }
            notifyDownloadComplete(file.id)
        } else if (oldFile?.local?.isDownloadingActive == true && !file.local.isDownloadingActive) {
            val type = fileDownloadTypes[file.id]
            if (type == DownloadType.STICKER || manualDownloadIds.contains(file.id)) {
                scope.appScope.launch(dispatcherProvider.default) {
                    enqueue(
                        fileId = file.id,
                        priority = if (type == DownloadType.STICKER) 32 else calculatePriority(file.id),
                        type = type ?: DownloadType.DEFAULT
                    )
                }
            } else {
                manualDownloadIds.remove(file.id)
            }
        }

        if (file.remote.isUploadingCompleted) {
            notifyUploadComplete(file.id)
        }
    }

    fun isFileQueued(fileId: Int) = pendingRequests.containsKey(fileId) || activeRequests.containsKey(fileId)

    fun setChatOpened(chatId: Long) {
        openChatIds.add(chatId)
        activeChatId = chatId
        flushIrrelevantBackgroundDownloads()
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
        synchronous: Boolean = false,
        ignoreSuppression: Boolean = false
    ) {
        Log.d(
            "DownloadDebug",
            "queue.enqueue: fileId=$fileId priority=$priority type=$type offset=$offset limit=$limit sync=$synchronous ignoreSuppression=$ignoreSuppression suppressed=${
                suppressedAutoDownloadIds.contains(
                    fileId
                )
            }"
        )
        scope.appScope.launch(dispatcherProvider.default) {
            if (!ignoreSuppression && suppressedAutoDownloadIds.contains(fileId)) {
                Log.d("DownloadDebug", "queue.enqueue.skippedBySuppression: fileId=$fileId")
                return@launch
            }

            val isManualRequest = priority >= 32
            if (isManualRequest) manualDownloadIds.add(fileId)

            val cooldownUntil = notFoundCooldownUntil[fileId]
            if (!isManualRequest && cooldownUntil != null && cooldownUntil > System.currentTimeMillis()) {
                return@launch
            }
            if (cooldownUntil != null && cooldownUntil <= System.currentTimeMillis()) {
                notFoundCooldownUntil.remove(fileId)
            }

            if (registry.getMessages(fileId).isEmpty()) registry.standaloneFileIds.add(fileId)
            if (type != DownloadType.DEFAULT || !fileDownloadTypes.containsKey(fileId)) {
                fileDownloadTypes[fileId] = type
            }

            val cached = cache.fileCache[fileId]
            if (cached?.local?.isDownloadingCompleted == true) {
                notifyDownloadComplete(fileId)
                manualDownloadIds.remove(fileId)
                return@launch
            }

            val resolvedType = if (type == DownloadType.DEFAULT) {
                fileDownloadTypes[fileId] ?: DownloadType.DEFAULT
            } else {
                type
            }

            val finalOffset = if (resolvedType == DownloadType.STICKER || resolvedType == DownloadType.VIDEO_NOTE) 0L else offset
            val finalLimit = if (resolvedType == DownloadType.STICKER || resolvedType == DownloadType.VIDEO_NOTE) 0L else limit

            val prio = calculatePriority(fileId).coerceAtLeast(priority)
            val isManual = manualDownloadIds.contains(fileId)
            val req = DownloadRequest(
                fileId = fileId,
                priority = prio,
                type = resolvedType,
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
                    val shouldKick = merged != active || cache.fileCache[fileId]?.local?.isDownloadingActive != true
                    if (shouldKick) {
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
                    if (!isManual && resolvedType == DownloadType.DEFAULT) {
                        val pendingDefaultCount = pendingRequests.values.count {
                            !it.isManual && it.type == DownloadType.DEFAULT
                        }
                        if (pendingDefaultCount >= maxPendingDefaultAutoDownloads) {
                            Log.d(
                                "DownloadDebug",
                                "queue.enqueue.skippedDefaultCap: fileId=$fileId pendingDefault=$pendingDefaultCount cap=$maxPendingDefaultAutoDownloads"
                            )
                            return@launch
                        }
                    }
                    pendingRequests[fileId] = req
                }
            }
            trigger.trySend(Unit)
        }
    }

    fun cancelDownload(fileId: Int, force: Boolean = false, suppress: Boolean = true) {
        if (!force && manualDownloadIds.contains(fileId)) return

        Log.d("DownloadDebug", "queue.cancel: fileId=$fileId force=$force suppress=$suppress")
        if (suppress) {
            suppressedAutoDownloadIds.add(fileId)
        }

        scope.appScope.launch(dispatcherProvider.io) {
            try {
                gateway.execute(TdApi.CancelDownloadFile(fileId, false))
            } catch (_: Exception) {
            }

            stateMutex.withLock {
                pendingRequests.remove(fileId)
                activeRequests.remove(fileId)
                failedRequests.remove(fileId)
            }
            Log.d("DownloadDebug", "queue.cancel.cleared: fileId=$fileId")
            notifyDownloadCancelled(fileId)
        }
    }

    fun clearSuppression(fileId: Int) {
        if (suppressedAutoDownloadIds.remove(fileId)) {
            Log.d("DownloadDebug", "queue.suppression.cleared: fileId=$fileId")
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

            toCancel.forEach { fileId -> cancelDownload(fileId, force = false, suppress = false) }
        }
    }

    private fun flushIrrelevantBackgroundDownloads() {
        scope.appScope.launch(dispatcherProvider.default) {
            val toCancel = mutableListOf<Int>()

            stateMutex.withLock {
                val candidateIds = HashSet<Int>(pendingRequests.size + activeRequests.size)
                candidateIds.addAll(pendingRequests.keys)
                candidateIds.addAll(activeRequests.keys)

                candidateIds.forEach { fileId ->
                    if (manualDownloadIds.contains(fileId)) return@forEach

                    val type = fileDownloadTypes[fileId]
                    if (type == DownloadType.STICKER || type == DownloadType.VIDEO_NOTE) return@forEach

                    val belongsToOpenChat = registry.getMessages(fileId).any { (chatId, _) ->
                        openChatIds.contains(chatId)
                    }

                    if (!belongsToOpenChat) {
                        toCancel.add(fileId)
                    }
                }
            }

            toCancel.forEach { fileId -> cancelDownload(fileId, force = false, suppress = false) }
        }
    }
}