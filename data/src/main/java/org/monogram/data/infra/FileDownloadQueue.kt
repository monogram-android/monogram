package org.monogram.data.infra

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import org.monogram.core.DispatcherProvider
import org.monogram.data.chats.ChatCache
import org.monogram.data.core.coRunCatching
import org.monogram.data.gateway.TdLibException
import org.monogram.data.gateway.TelegramGateway
import org.monogram.domain.repository.MediaAutoDownloadPolicy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class FileDownloadQueue(
    private val gateway: TelegramGateway,
    val registry: FileMessageRegistry,
    private val cache: ChatCache,
    private val scope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider
) : FileUpdateQueue {
    interface Observer {
        fun onDownloadCancelled(fileId: Int)
    }

    enum class DownloadType { VIDEO, GIF, STICKER, VIDEO_NOTE, DEFAULT }

    enum class MediaKind { PHOTO, VIDEO, GIF, VOICE, VIDEO_NOTE, DOCUMENT, AUDIO, STICKER, OTHER }
    enum class DemandRole { PRIMARY, PREVIEW, MANUAL_ONLY }
    data class MediaDescriptor(
        val kind: MediaKind,
        val role: DemandRole,
        val size: Long,
        val supportsStreaming: Boolean = false
    )

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
            // Match TDLib: among equal priorities the most recent request wins (LIFO).
            // `availableAt` is deliberately not a ranking key -- it expresses readiness
            // (backoff/cooldown) and is already enforced as a filter in dispatchTasks().
            // Ranking by it would keep this ordering FIFO and defeat the recency rule.
            return other.createdAt.compareTo(createdAt)
        }
    }

    private val stateMutex = Mutex()
    private val pendingRequests = ConcurrentHashMap<Int, DownloadRequest>()
    private val activeRequests = ConcurrentHashMap<Int, DownloadRequest>()
    private val failedRequests = ConcurrentHashMap<Int, DownloadRequest>()
    private val notFoundCooldownUntil = ConcurrentHashMap<Int, Long>()

    private val fileDownloadTypes = ConcurrentHashMap<Int, DownloadType>()
    private val mediaDescriptors = ConcurrentHashMap<Int, MediaDescriptor>()
    private val manualDownloadIds = ConcurrentHashMap.newKeySet<Int>()
    private val suppressedAutoDownloadIds = ConcurrentHashMap.newKeySet<Int>()
    private val downloadWaiters = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
    private val uploadWaiters = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
    private val lastProgressAt = ConcurrentHashMap<Int, Long>()
    private val stalledRecoveryAt = ConcurrentHashMap<Int, Long>()

    private val openChatIds = ConcurrentHashMap.newKeySet<Long>()
    private val visibleMessageIds = ConcurrentHashMap<Long, Set<Long>>()
    private val nearbyMessageIds = ConcurrentHashMap<Long, Set<Long>>()
    private val autoDownloadPolicies = ConcurrentHashMap<Long, MediaAutoDownloadPolicy>()

    @Volatile
    private var activeChatId: Long = 0L

    private val notFoundCooldownMs = TimeUnit.MINUTES.toMillis(2)
    private val maxAutoParallelDownloads = 8
    private val maxLargeAutoParallelDownloads = 2
    private val maxPendingDefaultAutoDownloads = 64
    private val stickerStallMs = 20_000L
    private val defaultStallMs = 35_000L
    private val stalledRecoveryCooldownMs = 12_000L

    private val trigger = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var observer: Observer? = null

    init {
        scope.launch(dispatcherProvider.default) {
            while (isActive) {
                trigger.receive()
                coRunCatching { dispatchTasks() }
                    .onFailure { Log.e("FileDownloadQueue", "dispatchTasks failed", it) }
            }
        }

        scope.launch(dispatcherProvider.default) {
            while (isActive) {
                delay(15_000)
                coRunCatching { recoverStalledDownloads() }
                    .onFailure { Log.e("FileDownloadQueue", "recoverStalledDownloads failed", it) }
            }
        }

        scope.launch(dispatcherProvider.default) {
            while (isActive) {
                delay(TimeUnit.MINUTES.toMillis(5))
                coRunCatching { cleanupDeadState() }
                    .onFailure { Log.e("FileDownloadQueue", "cleanupDeadState failed", it) }
            }
        }
    }

    private suspend fun dispatchTasks() {
        val tasksToStart = mutableListOf<DownloadRequest>()
        val now = System.currentTimeMillis()

        stateMutex.withLock {
            var autoCount = activeRequests.values.count { !it.isManual }
            var largeAutoCount =
                activeRequests.values.count { !it.isManual && it.type.isLargeMedia() }

            val candidates = pendingRequests.values
                .filter { it.availableAt <= now }
                .sorted()

            for (req in candidates) {
                if (autoCount >= maxAutoParallelDownloads) break
                val isLargeAuto = req.type.isLargeMedia()
                val canStart = !isLargeAuto || largeAutoCount < maxLargeAutoParallelDownloads

                if (canStart) {
                    autoCount++
                    if (isLargeAuto) largeAutoCount++
                    pendingRequests.remove(req.fileId)
                    activeRequests[req.fileId] = req
                    tasksToStart.add(req)
                }
            }
        }

        // No inter-start gap: awaiting one here serialised every start and, because `trigger` is
        // CONFLATED, an urgent enqueue arriving mid-dispatch could have its wake-up coalesced
        // away. TDLib already paces part issuance itself (DelayDispatcher, 50ms -> 3ms ramp).
        for (task in tasksToStart) {
            scope.launch(dispatcherProvider.io) {
                processDownload(task)
            }
        }

        // A conflated trigger can drop a wake-up that arrived while we were dispatching, which
        // would strand ready work until the 15s stall sweep. Re-arm only when this pass actually
        // started something and more is waiting -- gating on progress keeps this from spinning
        // when the backlog is blocked purely by slot exhaustion.
        if (tasksToStart.isNotEmpty() && pendingRequests.isNotEmpty()) {
            trigger.trySend(Unit)
        }
    }

    private suspend fun processDownload(req: DownloadRequest) {
        val fileId = req.fileId

        if (!isStillRelevant(fileId)) {
            finishTask(req)
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
                if (req.limit > 0L) {
                    waitForRequestedRange(req)
                } else {
                    deferred.await()
                }
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
            finishTask(req)

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
        if (nextRetry <= MAX_RECOVERY_RETRIES) {
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
            scope.launch(dispatcherProvider.default) {
                delay(backoffMs)
                trigger.trySend(Unit)
            }
        } else {
            failedRequests[req.fileId] = req
            downloadWaiters.remove(req.fileId)?.cancel()
        }
    }

    private suspend fun handleNotFoundDownloadFailure(req: DownloadRequest) {
        val nextRetry = req.retryCount + 1
        val cooldownMs = notFoundCooldownMs
        val availableAt = System.currentTimeMillis() + cooldownMs
        notFoundCooldownUntil[req.fileId] = availableAt

        if (nextRetry <= MAX_RECOVERY_RETRIES) {
            val nextReq = req.copy(retryCount = nextRetry, availableAt = availableAt)
            stateMutex.withLock {
                pendingRequests[req.fileId] = nextReq
                failedRequests.remove(req.fileId)
            }
            trigger.trySend(Unit)
            scope.launch(dispatcherProvider.default) {
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
        return (baseMs * attempt).coerceAtMost(capMs)
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
                if (req.retryCount >= MAX_RECOVERY_RETRIES) {
                    activeRequests.remove(req.fileId, req)
                    failedRequests[req.fileId] = req
                    downloadWaiters.remove(req.fileId)?.cancel()
                    return@forEach
                }
                val recoveredAt = stalledRecoveryAt[req.fileId] ?: 0L
                if (now - recoveredAt < stalledRecoveryCooldownMs) return@forEach
                stalledRecoveryAt[req.fileId] = now

                scope.launch(dispatcherProvider.default) {
                    val recovered = stateMutex.withLock {
                        val active = activeRequests[req.fileId] ?: return@withLock false
                        if (active.createdAt != req.createdAt || active.availableAt != req.availableAt) return@withLock false

                        activeRequests.remove(req.fileId)
                        pendingRequests[req.fileId] = mergeRequests(
                            pendingRequests[req.fileId] ?: req,
                            req.copy(
                                priority = if (req.type == DownloadType.STICKER) maxOf(req.priority, 32) else maxOf(req.priority, 16),
                                availableAt = System.currentTimeMillis() + 250L,
                                createdAt = System.currentTimeMillis(),
                                retryCount = req.retryCount + 1
                            )
                        )
                        true
                    }

                    if (recovered) {
                        coRunCatching {
                            withContext(dispatcherProvider.io) {
                                gateway.execute(TdApi.CancelDownloadFile(req.fileId, true))
                            }
                        }
                        lastProgressAt[req.fileId] = System.currentTimeMillis()
                        trigger.trySend(Unit)
                    }
                }
            }
        }
    }

    private suspend fun finishTask(request: DownloadRequest) {
        stateMutex.withLock {
            activeRequests.remove(request.fileId, request)
        }
        stalledRecoveryAt.remove(request.fileId)
        trigger.trySend(Unit)
    }

    private suspend fun waitForRequestedRange(req: DownloadRequest) {
        while (true) {
            val cached = cache.fileCache[req.fileId]
            if (cached?.local?.isDownloadingCompleted == true) return

            val prefix = withContext(dispatcherProvider.io) {
                coRunCatching {
                    gateway.execute(TdApi.GetFileDownloadedPrefixSize(req.fileId, req.offset))
                }.getOrNull()
            }
            val available = (prefix?.size ?: 0L).coerceAtLeast(0L)
            if (available >= req.limit) return

            delay(80L)
        }
    }

    override fun updateFileCache(file: TdApi.File) {
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
            scope.launch {
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

    fun getCachedFile(fileId: Int): TdApi.File? = cache.fileCache[fileId]

    fun getCachedPath(fileId: Int): String? =
        cache.fileCache[fileId]?.local?.path?.takeIf { it.isNotEmpty() }

    fun setChatOpened(chatId: Long) {
        openChatIds.add(chatId)
        activeChatId = chatId
        flushIrrelevantBackgroundDownloads()
    }

    fun setChatClosed(chatId: Long) {
        openChatIds.remove(chatId)
        visibleMessageIds.remove(chatId)
        nearbyMessageIds.remove(chatId)
        autoDownloadPolicies.remove(chatId)
        if (activeChatId == chatId) activeChatId = 0L
        registry.unregisterChat(chatId)
        cancelIrrelevantDownloads()
    }

    fun updateVisibleRange(
        chatId: Long,
        visible: List<Long>,
        nearby: List<Long>,
        policy: MediaAutoDownloadPolicy
    ) {
        visibleMessageIds[chatId] = visible.toSet()
        nearbyMessageIds[chatId] = nearby.toSet()
        autoDownloadPolicies[chatId] = policy
        activeChatId = chatId

        scope.launch(dispatcherProvider.default) {
            cancelIrrelevantDownloads()
            (visible + nearby).forEach { messageId ->
                registry.getFileIdsForMessage(chatId, messageId).forEach { fileId ->
                    fileDownloadTypes[fileId]?.takeIf { isAutoDownloadAllowed(fileId) }
                        ?.let { type ->
                        enqueue(fileId, calculatePriority(fileId), type)
                    }
                }
            }
        }
    }

    fun registerFileForMessage(
        fileId: Int,
        chatId: Long,
        messageId: Long,
        type: DownloadType,
        descriptor: MediaDescriptor? = null
    ) {
        if (fileId == 0) return
        registry.register(fileId, chatId, messageId)
        if (type != DownloadType.DEFAULT || !fileDownloadTypes.containsKey(fileId)) {
            fileDownloadTypes[fileId] = type
        }
        descriptor?.let { mediaDescriptors[fileId] = it }
    }

    fun enqueue(
        fileId: Int,
        priority: Int = 1,
        type: DownloadType = DownloadType.DEFAULT,
        offset: Long = 0,
        limit: Long = 0,
        synchronous: Boolean = false,
        ignoreSuppression: Boolean = false,
        userInitiated: Boolean = false
    ) {
        scope.launch(dispatcherProvider.default) {
            if (!ignoreSuppression && suppressedAutoDownloadIds.contains(fileId)) {
                return@launch
            }

            // User intent is an explicit signal, not something inferred from a magic priority.
            // It used to be `priority >= 32`, but viewport auto-download also passes 32, so every
            // thumbnail scrolled past was marked "manual" and became un-evictable.
            val isManualRequest = userInitiated
            if (isManualRequest) manualDownloadIds.add(fileId)

            val registeredMessages = registry.getMessages(fileId)
            if (
                !isManualRequest &&
                !synchronous &&
                registeredMessages.isNotEmpty() &&
                !hasViewportDemand(fileId)
            ) {
                return@launch
            }

            val cooldownUntil = notFoundCooldownUntil[fileId]
            if (!isManualRequest && cooldownUntil != null && cooldownUntil > System.currentTimeMillis()) {
                return@launch
            }
            if (cooldownUntil != null && cooldownUntil <= System.currentTimeMillis()) {
                notFoundCooldownUntil.remove(fileId)
            }

            if (registeredMessages.isEmpty()) registry.standaloneFileIds.add(fileId)
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
                    val shouldKick = merged != active
                    if (shouldKick) {
                        activeRequests[fileId] = merged
                        scope.launch(dispatcherProvider.io) {
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

                // Explicit user actions bypass the automatic-download caps.
                if (userInitiated) {
                    pendingRequests.remove(fileId)
                    activeRequests[fileId] = req
                    scope.launch(dispatcherProvider.io) {
                        processDownload(req)
                    }
                    return@launch
                }

                val pending = pendingRequests[fileId]
                if (pending != null) {
                    pendingRequests[fileId] = mergeRequests(pending, req)
                } else {
                    // Never silently drop a synchronous request: callers await waitForDownload(),
                    // and abandoning it here leaves that deferred uncompleted forever.
                    if (!isManual && !synchronous && resolvedType == DownloadType.DEFAULT) {
                        val pendingDefaultCount = pendingRequests.values.count {
                            !it.isManual && it.type == DownloadType.DEFAULT
                        }
                        if (req.priority < 32 && pendingDefaultCount >= maxPendingDefaultAutoDownloads) {
                            downloadWaiters.remove(fileId)?.cancel()
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

        if (suppress) {
            suppressedAutoDownloadIds.add(fileId)
        }

        scope.launch(dispatcherProvider.io) {
            try {
                gateway.execute(TdApi.CancelDownloadFile(fileId, !force))
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

    fun setObserver(observer: Observer?) {
        this.observer = observer
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

    override fun notifyDownloadComplete(fileId: Int) {
        downloadWaiters.remove(fileId)?.complete(Unit)
    }

    fun notifyDownloadCancelled(fileId: Int) {
        downloadWaiters.remove(fileId)?.cancel()
        observer?.onDownloadCancelled(fileId)
    }

    override fun notifyUploadComplete(fileId: Int) {
        uploadWaiters.remove(fileId)?.complete(Unit)
    }

    fun notifyUploadCancelled(fileId: Int) {
        uploadWaiters.remove(fileId)?.cancel()
    }

    private fun isStillRelevant(fileId: Int): Boolean {
        if (manualDownloadIds.contains(fileId)) return true
        if (registry.sponsoredFileIds.contains(fileId)) return true
        if (registry.standaloneFileIds.contains(fileId)) return true
        return hasViewportDemand(fileId)
    }

    private fun hasViewportDemand(fileId: Int): Boolean =
        registry.getMessages(fileId).any { (chatId, msgId) ->
            openChatIds.contains(chatId) &&
                    isAutoDownloadAllowed(fileId, chatId, msgId) &&
                    (visibleMessageIds[chatId]?.contains(msgId) == true ||
                            nearbyMessageIds[chatId]?.contains(msgId) == true)
        }

    private fun isAutoDownloadAllowed(fileId: Int): Boolean =
        registry.getMessages(fileId).any { (chatId, messageId) ->
            isAutoDownloadAllowed(fileId, chatId, messageId)
        }

    private fun isAutoDownloadAllowed(fileId: Int, chatId: Long, messageId: Long): Boolean {
        val policy = autoDownloadPolicies[chatId] ?: return false
        val descriptor = mediaDescriptors[fileId] ?: return false
        if (!policy.enabled || descriptor.role == DemandRole.MANUAL_ONLY || descriptor.supportsStreaming) return false
        val nearbyOnly = nearbyMessageIds[chatId]?.contains(messageId) == true &&
                visibleMessageIds[chatId]?.contains(messageId) != true
        if (nearbyOnly && descriptor.role != DemandRole.PREVIEW && descriptor.kind != MediaKind.STICKER) return false
        if ((descriptor.kind == MediaKind.DOCUMENT || descriptor.kind == MediaKind.AUDIO) && !policy.allowFiles) return false
        val cap = when (descriptor.kind) {
            MediaKind.PHOTO, MediaKind.STICKER -> 10L * MIB
            MediaKind.GIF -> 15L * MIB
            MediaKind.VOICE, MediaKind.VIDEO_NOTE -> 20L * MIB
            MediaKind.VIDEO -> 50L * MIB
            MediaKind.DOCUMENT, MediaKind.AUDIO -> 20L * MIB
            MediaKind.OTHER -> 10L * MIB
        }
        return descriptor.size in 1..cap || descriptor.role == DemandRole.PREVIEW
    }

    private fun calculatePriority(fileId: Int): Int {
        if (manualDownloadIds.contains(fileId)) return 32

        val messages = registry.getMessages(fileId)
        var max = 1
        val type = fileDownloadTypes[fileId]

        // Priority only does work when values differ. These used to saturate at 32 -- the same
        // value as an explicit user action -- which left TDLib nothing to arbitrate and reduced
        // every scheduling decision to the tie-break. 32 is now reserved for real user intent.
        if (type == DownloadType.STICKER || type == DownloadType.VIDEO_NOTE) {
            max = 8
        }

        messages.forEach { (chatId, msgId) ->
            val isVisible = visibleMessageIds[chatId]?.contains(msgId) == true
            val isNearby = nearbyMessageIds[chatId]?.contains(msgId) == true

            var p = 1
            if (isVisible) {
                p = if (mediaDescriptors[fileId]?.role == DemandRole.PREVIEW) 16 else 24
            } else if (isNearby) {
                p = 8
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

    private fun DownloadType.isLargeMedia(): Boolean =
        this == DownloadType.VIDEO || this == DownloadType.VIDEO_NOTE || this == DownloadType.GIF

    private fun cancelIrrelevantDownloads() {
        scope.launch(dispatcherProvider.default) {
            // Scan active downloads too, not just pending ones. An in-flight download that the
            // user has scrolled past used to hold its slot until completion or the 3-minute
            // timeout, which is what let stale work block foreground requests.
            val toCancel = LinkedHashSet<Int>()

            for ((fileId, _) in pendingRequests) {
                if (!isStillRelevant(fileId)) toCancel.add(fileId)
            }
            for ((fileId, _) in activeRequests) {
                if (!isStillRelevant(fileId)) toCancel.add(fileId)
            }

            toCancel.forEach { fileId -> cancelDownload(fileId, force = false, suppress = false) }
        }
    }

    private fun flushIrrelevantBackgroundDownloads() {
        scope.launch(dispatcherProvider.default) {
            val toCancel = mutableListOf<Int>()

            stateMutex.withLock {
                val candidateIds = HashSet<Int>(pendingRequests.size + activeRequests.size)
                candidateIds.addAll(pendingRequests.keys)
                candidateIds.addAll(activeRequests.keys)

                candidateIds.forEach { fileId ->
                    if (manualDownloadIds.contains(fileId)) return@forEach

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

    private companion object {
        const val MIB = 1024L * 1024L
        const val MAX_RECOVERY_RETRIES = 1
    }
}
