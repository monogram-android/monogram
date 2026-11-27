package org.monogram.network.http

import android.graphics.BitmapFactory
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.monogram.core.common.AppLog
import org.monogram.core.common.MediaCacheLimit
import org.monogram.core.common.Outcome
import org.monogram.core.common.PerfLog
import org.monogram.core.common.WorkOwnership
import org.monogram.core.common.perfOp
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.INSTANT_VIEW_MEDIA_MSG_ID
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.core.models.ScheduledMessage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

fun photoDisplayCacheKey(mediaCacheKey: String): String = "$mediaCacheKey:display:v2"

fun legacyPhotoDisplayCacheKey(mediaCacheKey: String): String = "$mediaCacheKey:display"

fun peerPhotoSmallCacheKey(photoCacheKey: String): String =
    if (photoCacheKey.endsWith(":small") || photoCacheKey.endsWith(":video")) {
        photoCacheKey
    } else {
        "$photoCacheKey:small"
    }

internal fun streamPrefixPart(
    directory: File,
    cacheKey: String,
    offset: Long,
): File = FileCache(directory, maintainOnInit = false).fileFor(streamPartKey(cacheKey, offset))

internal fun streamPartKey(
    cacheKey: String,
    offset: Long,
): String = "$cacheKey:stream:v2:$offset"

internal fun streamMediaKey(message: Message): String? = message.mediaCacheKey?.let {
    "$it:chat:${message.id.chatId.value}:message:${message.id.id}:size:${message.fileSize ?: 0}"
}

/** A prefix part the player can read without fetching again. */
internal fun reusableStreamPrefix(
    file: File?,
    offset: Long = 0,
    size: Long? = null,
): File? {
    val expected = size?.takeIf { it > 0 }?.let { minOf(512L * 1024, it - offset) } ?: (512L * 1024)
    return file?.takeIf { expected > 0 && it.isFile && it.length() == expected }
}

fun mediaThumbCacheKey(mediaCacheKey: String): String =
    if (mediaCacheKey.endsWith(":thumb")) mediaCacheKey else "$mediaCacheKey:thumb"

data class UserDownload(
    val key: String,
    val name: String,
    val totalBytes: Long? = null,
)

/** One owner's attachment to a deduplicated transfer. */
class MediaConsumer internal constructor(
    internal val id: Long,
    internal val key: String,
) {
    internal val revision = AtomicInteger()
}

private val NO_SYNTHETIC_THUMB_KINDS = setOf("document", "audio", "voice")

fun interface TelegramMediaFetcher {
    suspend fun fetchMessageMedia(
        chatId: PeerId,
        messageId: Int,
        destPath: String,
        kind: MediaFetchKind,
        priority: Int,
    ): Outcome<String>

    suspend fun fetchExpectedMedia(
        chatId: PeerId,
        messageId: Int,
        destPath: String,
        kind: MediaFetchKind,
        priority: Int,
        expectedCacheKey: String,
    ): Outcome<String> = fetchMessageMedia(chatId, messageId, destPath, kind, priority)

    /** A visible consumer joined the transfer writing [destPath]. */
    fun promote(
        destPath: String,
        priority: Int,
    ) = Unit
}

fun interface TelegramInlineThumbPeek {
    fun peek(
        chatId: PeerId,
        messageId: Int,
    ): ByteArray?
}

fun interface TelegramPeerInlineThumbPeek {
    fun peek(peerId: PeerId): ByteArray?
}

class MediaRepository(
    val cacheRoot: File,
    private val httpClientFactory: () -> io.ktor.client.HttpClient = { HttpModule.createClient() },
    private val telegramFetcher: TelegramMediaFetcher? = null,
    private val customEmojiFetcher: (suspend (Long, String, Int) -> Outcome<String>)? = null,
    private val maxConcurrentTelegram: Int = TELEGRAM_WORKERS,
    private val telegramChunkFetcher: TelegramChunkFetcher? = null,
    private val inlineThumbPeek: TelegramInlineThumbPeek? = null,
    private val peerInlineThumbPeek: TelegramPeerInlineThumbPeek? = null,
    cacheLimitBytes: Long? = MediaCacheLimit.DEFAULT.bytes,
) {
    private val streamRoot = cacheRoot

    /** OpenStreetMap tiles for the location and venue cards; cached on disk. */
    val mapTiles = MapTileStore(cacheRoot, httpClientFactory)

    @OptIn(UnstableApi::class)
    fun createMessageDataSourceFactory(message: Message): DataSource.Factory =
        TelegramVideoDataSource.Factory(message, streamRoot, telegramChunkFetcher, cache)

    @OptIn(UnstableApi::class)
    fun createCachedDataSourceFactory(key: String): DataSource.Factory =
        DataSource.Factory { LeasedFileDataSource(cache, key) }

    private val cache = FileCache(
        cacheRoot,
        maxBytes = cacheLimitBytes ?: 1L,
        maintainOnInit = false,
        enforceSize = cacheLimitBytes != null,
    )
    private val progress = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val generation = MutableStateFlow(0L)
    private val cacheClearedGeneration = MutableStateFlow(0L)
    private val keyedGenerations = ConcurrentHashMap<String, KeyedGeneration>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    internal var cacheWorkThread: Thread? = null

    init {
        progressSink = { path, downloaded -> onNativeProgress(path, downloaded) }
        scope.launch {
            cacheWorkThread = Thread.currentThread()
            cache.maintain()
            cache.trimIfNeeded()
        }
    }

    private val telegramMutex = Mutex()
    private val telegramWake = Channel<Unit>(Channel.CONFLATED)
    private val thumbWake = Channel<Unit>(Channel.CONFLATED)
    private val telegramPending = PriorityQueue<QueuedTelegram>()
    private val telegramJobs = HashMap<String, TelegramJob>()
    private val pendingConsumerPriority = HashMap<Long, Int>()
    private val nextConsumerId = java.util.concurrent.atomic.AtomicLong()
    private val telegramWorkers = mutableListOf<Job>()
    private var telegramSequence = 0L

    @Volatile
    private var telegramStopped = false
    private val progressPaths = ConcurrentHashMap<String, String>()

    val downloadProgress: StateFlow<Map<String, Long>> = progress.asStateFlow()
    private val userDownloadsState = MutableStateFlow<Map<String, UserDownload>>(emptyMap())
    val userDownloads: StateFlow<Map<String, UserDownload>> = userDownloadsState.asStateFlow()
    val cacheGeneration: StateFlow<Long> = generation.asStateFlow()

    fun isUserDownload(key: String): Boolean = key in userDownloadsState.value

    fun protectCacheKeys(
        owner: String,
        keys: Collection<String>,
    ) {
        cache.setProtectedKeys(owner, keys)
    }

    private val inlineThumbs =
        java.util.LinkedHashMap<String, ByteArray>(INLINE_THUMB_CAP, 0.75f, true)

    fun inlineThumbJpeg(message: Message): ByteArray? {
        val key =
            message.thumbCacheKey ?: message.mediaCacheKey?.let(::mediaThumbCacheKey) ?: return null
        cachedInlineThumb(key)?.let { return it }
        val bytes = inlineThumbPeek?.peek(message.id.chatId, message.id.id) ?: return null
        if (bytes.isNotEmpty()) {
            rememberInlineThumb(key, bytes)
            PerfLog.event("stripped_inline")
        }
        return bytes.takeIf { it.isNotEmpty() }
    }

    /** Stripped or cached avatar JPEG indexed at `(peerId, messageId = 0)`. No download. */
    fun peerInlineThumbJpeg(
        peerId: PeerId,
        cacheKey: String? = null,
    ): ByteArray? {
        val key = cacheKey?.let { "$it:thumb" }
        key?.let(::cachedInlineThumb)?.let { return it }
        val bytes = peerInlineThumbPeek?.peek(peerId) ?: return null
        if (bytes.isNotEmpty()) {
            if (key != null) rememberInlineThumb(key, bytes)
            PerfLog.event("stripped_peer")
        }
        return bytes.takeIf { it.isNotEmpty() }
    }

    private fun cachedInlineThumb(key: String): ByteArray? = synchronized(inlineThumbs) {
        inlineThumbs[key]
    }

    private fun rememberInlineThumb(
        key: String,
        bytes: ByteArray,
    ) = synchronized(inlineThumbs) {
        inlineThumbs[key] = bytes
        dropEldest(inlineThumbs, INLINE_THUMB_CAP)
    }

    private fun markCached(key: String) {
        generation.update { it + 1 }
        keyedGenerations[key]?.generation?.update { it + 1 }
    }

    private fun markAllCachedFilesChanged() {
        generation.update { it + 1 }
        cacheClearedGeneration.update { it + 1 }
    }

    /**
     * Emits when one of [keys] changes or the complete cache is cleared.
     *
     * Key entries live only while a UI collector needs them, so scrolling through
     * media cannot retain one state flow per cache file for the app lifetime.
     */
    fun cacheGeneration(keys: Iterable<String>): Flow<Long> = flow {
        val names = keys.asSequence().filter(String::isNotBlank).distinct().toList()
        val observed = names.map { key ->
                key to keyedGenerations.compute(key) { _, current ->
                    (current ?: KeyedGeneration()).also { it.collectors.incrementAndGet() }
                }!!
            }.toList()
        try {
            val sources = observed.map { it.second.generation } + cacheClearedGeneration
            val baselines = sources.map { it.value }
            var version = generation.value
            emit(version)
            merge(
                *sources.zip(baselines).map { (source, baseline) ->
                        source.dropWhile { it == baseline }
                    }.toTypedArray(),
            ).collect { emit(++version) }
        } finally {
            observed.forEach { (key, entry) ->
                keyedGenerations.computeIfPresent(key) { _, current ->
                    if (current === entry && entry.collectors.decrementAndGet() == 0) null else current
                }
            }
        }
    }

    fun cacheGeneration(key: String): Flow<Long> = cacheGeneration(listOf(key))

    private class KeyedGeneration {
        val generation = MutableStateFlow(0L)
        val collectors = AtomicInteger(0)
    }

    fun progressBytes(key: String): Long = progress.value[key] ?: 0L

    private val queueDelegate = lazy {
        DownloadQueue(
            client = httpClientFactory(),
            scope = scope,
            maxConcurrent = 2,
        ).also { it.start() }
    }
    private val queue by queueDelegate

    fun cachedFile(key: String): File? = cache.get(key)

    fun acquireCachedFile(key: String): FileCache.Lease? = cache.acquire(key)

    private val usedBytes = java.util.concurrent.atomic.AtomicLong()

    /** Bytes of cache files a consumer actually opened. Prefetch can compare this with transferred bytes. */
    fun usedByteCount(): Long = usedBytes.get()

    /** Lease by physical file, for consumers that only hold the resolved path. */
    fun leaseFile(file: File): AutoCloseable? {
        val lease = cache.acquireFile(file)
        if (lease != null) usedBytes.addAndGet(file.length())
        return lease
    }

    suspend fun removeCachedFile(key: String): Boolean = withContext(Dispatchers.IO) {
        cache.remove(key).also { removed -> if (removed) markCached(key) }
    }

    suspend fun cacheThumbnail(
        key: String,
        bytes: ByteArray,
        chatId: Long,
        mediaKind: String?,
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            cache.put(
                key,
                bytes,
                chatId,
                FileCache.inferKind(key, mediaKind),
            )
        }.onSuccess { markCached(key) }.getOrNull()
    }

    fun cachedAvatar(peerId: PeerId): File? = cache.get(avatarPeerKey(peerId))

    private fun avatarPeerKey(peerId: PeerId): String = "avatar:${peerId.value}"

    private fun rememberPeerAvatar(
        peerId: PeerId,
        file: File,
    ) {
        val alias = avatarPeerKey(peerId)
        val existing = cache.get(alias)
        if (existing != null) {
            if (existing.canonicalFile == file.canonicalFile) return
            runCatching {
                cache.putFile(
                    alias,
                    file,
                    peerId.value,
                    FileCache.KIND_PHOTOS,
                )
            }.onSuccess { markCached(alias) }
            return
        }
        runCatching {
            cache.putFile(alias, file, peerId.value, FileCache.KIND_PHOTOS)
        }.onSuccess { markCached(alias) }
    }

    fun clearCache(): Long {
        val freed = cache.clear()
        synchronized(inlineThumbs) { inlineThumbs.clear() }
        markAllCachedFilesChanged()
        return freed
    }

    fun setCacheLimit(bytes: Long?) {
        scope.launch {
            cacheWorkThread = Thread.currentThread()
            cache.setMaxBytes(bytes)
        }
    }

    fun cacheSizeBytes(): Long = cache.totalBytes()

    fun storageAccounting(): StorageAccounting = cache.accounting()

    fun inflightBytes(): Long = cache.inflightBytes()

    /** Releases an explicit download so ordinary cache trimming may reclaim it. */
    fun unpinDownload(key: String) = cache.unpin(key)

    fun cacheByKind(): Map<String, Long> = cache.usageByKind()

    fun cacheByChat(): Map<Long, Long> = cache.usageByChat()

    fun clearChatCache(chatId: Long): Long = cache.clearChat(chatId).also {
        if (it > 0L) markAllCachedFilesChanged()
    }

    fun clearKindCache(kind: String): Long = cache.clearKind(kind).also {
        if (it > 0L) markAllCachedFilesChanged()
    }

    suspend fun ensureLocalMessageMedia(
        message: Message,
        priority: Int = MediaPriority.DEFAULT,
        retain: Boolean = false,
    ): Outcome<File> = ensureMessageMedia(message, priority, retain, scheduled = false)

    suspend fun ensureLocalScheduledMessageMedia(
        scheduled: ScheduledMessage,
        priority: Int = MediaPriority.VISIBLE,
    ): Outcome<File> = ensureMessageMedia(
        scheduled.message.copy(id = scheduled.mediaMessageId),
        priority,
        retain = false,
        scheduled = true,
    )

    private suspend fun ensureMessageMedia(
        message: Message,
        priority: Int,
        retain: Boolean,
        scheduled: Boolean,
    ): Outcome<File> {
        val key = message.mediaCacheKey ?: return Outcome.Err("message has no media key")
        return ensureLocalTelegramMedia(
            key = key,
            scheduled = scheduled,
            chatId = message.id.chatId,
            messageId = message.id.id,
            kind = MediaFetchKind.Full,
            mediaKind = message.mediaKind,
            priority = priority,
            name = message.fileName,
            totalBytes = message.fileSize,
            retain = retain,
        )
    }

    suspend fun ensureLocalMessageDisplay(
        message: Message,
        priority: Int = MediaPriority.DEFAULT,
    ): Outcome<File> {
        val key = message.mediaCacheKey ?: return Outcome.Err("message has no media key")
        return ensureLocalTelegramMedia(
            key = photoDisplayCacheKey(key),
            chatId = message.id.chatId,
            messageId = message.id.id,
            kind = MediaFetchKind.Display,
            mediaKind = message.mediaKind,
            priority = maxOf(priority, MediaPriority.DISPLAY),
            legacyDisplayKey = legacyPhotoDisplayCacheKey(key),
        )
    }

    /** First 2 MiB of a stream, not the whole document. */
    suspend fun ensureStreamingPrefix(
        message: Message,
        priority: Int = MediaPriority.VISIBLE,
    ): Outcome<File> {
        PerfLog.event("stream_prefix", "priority=$priority")
        val key = streamMediaKey(message) ?: return Outcome.Err("message has no media key")
        val fetcher = telegramChunkFetcher ?: return Outcome.Err("stream prefetch unavailable")
        val limit = message.fileSize?.takeIf { it > 0 }?.coerceAtMost(STREAM_PREFIX_BYTES)
            ?: STREAM_PREFIX_BYTES
        val queuePriority = minOf(priority, MediaPriority.VISIBLE - 1)
        var last: File? = null
        var offset = 0L
        while (offset < limit) {
            when (val result = fetchStreamingPart(message, key, offset, queuePriority, fetcher)) {
                is Outcome.Err -> {
                    return last?.let { Outcome.Ok(it) } ?: result
                }

                is Outcome.Ok -> {
                    last = result.value
                    if (last.length() < STREAM_PART) return result
                }
            }
            offset += STREAM_PART
        }
        if (last != null && message.mediaKind == "video") {
            try {
                prefetchMp4Metadata(message, key, queuePriority, fetcher)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                PerfLog.event("stream_metadata", "result=fallback")
            }
        }
        return last?.let { Outcome.Ok(it) } ?: Outcome.Err("empty stream prefix")
    }

    private suspend fun fetchStreamingPart(
        message: Message,
        key: String,
        offset: Long,
        priority: Int,
        fetcher: TelegramChunkFetcher,
    ): Outcome<File> {
        val partKey = streamPartKey(key, offset)
        val size = message.fileSize?.takeIf { it > 0 }

        fun reusable(file: File): Boolean = reusableStreamPrefix(file, offset, size) != null

        val result = enqueueTelegram(
            partKey,
            priority,
            chatId = message.id.chatId.value,
            large = isLargeDownload(size),
            acceptCached = ::reusable,
        ) {
            cache.get(partKey)?.takeIf(::reusable)?.let { return@enqueueTelegram Outcome.Ok(it) }
            streamRoot.mkdirs()
            val temporary = File.createTempFile("stream-$offset-", ".part", streamRoot)
            try {
                when (val fetched = fetcher.fetchExpected(
                    message.id.chatId,
                    message.id.id,
                    temporary.absolutePath,
                    offset,
                    checkNotNull(message.mediaCacheKey),
                )) {
                    is Outcome.Err -> {
                        fetched
                    }

                    is Outcome.Ok -> {
                        if (File(fetched.value).canonicalFile != temporary.canonicalFile || (size != null && !reusable(
                                temporary
                            )) || temporary.length() !in 1L..STREAM_PART
                        ) {
                            return@enqueueTelegram Outcome.Err("invalid stream prefix")
                        }
                        currentCoroutineContext().ensureActive()
                        java.io.RandomAccessFile(temporary, "rw").use { it.fd.sync() }
                        Outcome.Ok(
                            cache.putFile(
                                partKey, temporary, message.id.chatId.value, FileCache.KIND_VIDEOS
                            )
                        )
                    }
                }
            } finally {
                temporary.delete()
            }
        }
        if (result is Outcome.Err) (result.cause as? CancellationException)?.let { throw it }
        return result
    }

    private suspend fun prefetchMp4Metadata(
        message: Message,
        key: String,
        priority: Int,
        fetcher: TelegramChunkFetcher,
    ) {
        val size = message.fileSize?.takeIf { it > 0 } ?: return
        var remaining = 2L * 1024 * 1024
        val owner = "metadata:${java.util.UUID.randomUUID()}"
        val protected = LinkedHashSet<String>()

        suspend fun part(offset: Long): File? {
            val aligned = offset / STREAM_PART * STREAM_PART
            val partKey = streamPartKey(key, aligned)
            protected.add(partKey)
            cache.setProtectedKeys(owner, protected)
            reusableStreamPrefix(cache.get(partKey), aligned, size)?.let { return it }
            val charge = minOf(STREAM_PART, size - aligned)
            if (charge <= 0 || charge > remaining) return null
            remaining -= charge
            return when (val result =
                fetchStreamingPart(message, key, aligned, priority, fetcher)) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> null
            }
        }
        try {
            preloadMp4Metadata(size, read = { offset, count ->
                val bytes = ByteArray(count)
                var copied = 0
                while (copied < count) {
                    val position = offset + copied
                    val file = part(position) ?: break
                    val take = minOf(
                        (count - copied).toLong(),
                        file.length() - (position % STREAM_PART),
                    ).toInt()
                    if (take <= 0) break
                    java.io.RandomAccessFile(file, "r").use { input ->
                        input.seek(position % STREAM_PART)
                        input.readFully(bytes, copied, take)
                    }
                    copied += take
                }
                bytes.takeIf { copied == count }
            }, cover = { offset, length ->
                var position = offset
                var complete = true
                while (position < offset + length) {
                    if (part(position) == null) {
                        complete = false
                        break
                    }
                    position = (position / STREAM_PART + 1) * STREAM_PART
                }
                complete
            })
        } finally {
            cache.setProtectedKeys(owner, emptySet())
            cache.trimIfNeeded()
        }
    }

    suspend fun ensureLocalMessageThumb(
        message: Message,
        priority: Int = MediaPriority.THUMB,
    ): Outcome<File> = ensureMessageThumb(message, priority, scheduled = false)

    suspend fun ensureLocalScheduledMessageThumb(
        scheduled: ScheduledMessage,
        priority: Int = MediaPriority.THUMB,
    ): Outcome<File> = ensureMessageThumb(
        scheduled.message.copy(id = scheduled.mediaMessageId),
        priority,
        scheduled = true,
    )

    private suspend fun ensureMessageThumb(
        message: Message,
        priority: Int,
        scheduled: Boolean,
    ): Outcome<File> {
        val distinctThumb = message.thumbCacheKey?.takeUnless { it == message.mediaCacheKey }
        if (distinctThumb == null && message.mediaKind in NO_SYNTHETIC_THUMB_KINDS) {
            return Outcome.Err("no downloadable thumb")
        }
        val key =
            distinctThumb ?: message.mediaCacheKey?.let(::mediaThumbCacheKey) ?: return Outcome.Err(
                "message has no media key",
            )
        return ensureLocalTelegramMedia(
            key = key,
            chatId = message.id.chatId,
            messageId = message.id.id,
            kind = MediaFetchKind.Thumb,
            scheduled = scheduled,
            mediaKind = message.mediaKind,
            priority = priority,
        )
    }

    /** Profile avatars are indexed as `(peerId, messageId=0)` in the native media map. */
    suspend fun ensureLocalAvatar(
        peerId: PeerId,
        cacheKey: String,
        priority: Int = MediaPriority.DEFAULT,
        allowStaleFallback: Boolean = true,
    ): Outcome<File> {
        if (isVideoAvatarKey(cacheKey)) {
            return fetchFullAvatar(peerId, cacheKey, priority, allowStaleFallback)
        }
        cache.get(cacheKey)?.let { file ->
            rememberPeerAvatar(peerId, file)
            return Outcome.Ok(file)
        }
        val smallKey = peerPhotoSmallCacheKey(cacheKey)
        val fullPriority = if (allowStaleFallback) MediaPriority.IDLE else priority
        cache.get(smallKey)?.let { file ->
            scheduleFullAvatar(peerId, cacheKey, fullPriority)
            return Outcome.Ok(file)
        }
        if (allowStaleFallback) {
            cachedAvatar(peerId)?.let { file ->
                val displayPriority = priority.takeIf { it >= MediaPriority.VISIBLE }
                scheduleFullAvatar(peerId, cacheKey, fullPriority, displayPriority)
                return Outcome.Ok(file)
            }
        }
        when (val small = fetchSmallAvatar(peerId, cacheKey, priority)) {
            is Outcome.Ok -> {
                scheduleFullAvatar(peerId, cacheKey, fullPriority)
                return small
            }

            is Outcome.Err -> {
                Unit
            }
        }
        return fetchFullAvatar(peerId, cacheKey, priority, allowStaleFallback)
    }

    private suspend fun fetchFullAvatar(
        peerId: PeerId,
        cacheKey: String,
        priority: Int,
        allowStaleFallback: Boolean,
    ): Outcome<File> = when (val fetched = ensureLocalTelegramMedia(
        key = cacheKey,
        chatId = peerId,
        messageId = 0,
        kind = MediaFetchKind.Full,
        priority = priority,
    )) {
        is Outcome.Ok -> {
            rememberPeerAvatar(peerId, fetched.value)
            fetched
        }

        is Outcome.Err -> {
            if (isVideoAvatarKey(cacheKey) || !allowStaleFallback) {
                fetched
            } else {
                cachedAvatar(peerId)?.let { Outcome.Ok(it) } ?: fetched
            }
        }
    }

    private suspend fun fetchSmallAvatar(
        peerId: PeerId,
        cacheKey: String,
        priority: Int,
    ): Outcome<File> = ensureLocalTelegramMedia(
        key = peerPhotoSmallCacheKey(cacheKey),
        chatId = peerId,
        messageId = 0,
        kind = MediaFetchKind.Display,
        priority = priority,
    )

    private fun scheduleFullAvatar(
        peerId: PeerId,
        cacheKey: String,
        priority: Int,
        displayPriority: Int? = null,
    ) {
        if (isVideoAvatarKey(cacheKey) || cache.get(cacheKey) != null) return
        scope.launch {
            if (cache.get(cacheKey) != null) return@launch
            val fullPriority = if (displayPriority != null && fetchSmallAvatar(
                    peerId, cacheKey, displayPriority
                ) is Outcome.Err
            ) {
                displayPriority
            } else {
                priority
            }
            fetchFullAvatar(peerId, cacheKey, fullPriority, allowStaleFallback = false)
        }
    }

    fun openDownloadConsumer(key: String): MediaConsumer =
        MediaConsumer(nextConsumerId.incrementAndGet(), key)

    /** Changes this consumer's priority on the existing transfer. Does not join another job. */
    fun updateDownloadConsumer(
        consumer: MediaConsumer,
        priority: Int,
    ) {
        val revision = consumer.revision.incrementAndGet()
        scope.launch { applyDownloadConsumerPriority(consumer, priority, revision) }
    }

    internal suspend fun awaitDownloadConsumerPriority(
        consumer: MediaConsumer,
        priority: Int,
    ) {
        val revision = consumer.revision.incrementAndGet()
        applyDownloadConsumerPriority(consumer, priority, revision)
    }

    suspend fun ensureIndexedMedia(
        peerId: PeerId,
        messageId: Int,
        cacheKey: String,
        thumb: Boolean = false,
        priority: Int = MediaPriority.DEFAULT,
        mediaKind: String? = null,
        consumer: MediaConsumer? = null,
    ): Outcome<File> = ensureLocalTelegramMedia(
        key = cacheKey,
        chatId = peerId,
        messageId = messageId,
        kind = if (thumb) MediaFetchKind.Thumb else MediaFetchKind.Full,
        mediaKind = mediaKind,
        priority = priority,
        consumer = consumer,
    )

    suspend fun ensureCustomEmoji(
        documentId: Long,
        priority: Int = MediaPriority.DEFAULT,
        consumer: MediaConsumer? = null,
    ): Outcome<File> {
        val key = "emoji:$documentId"
        cache.get(key)?.let { return Outcome.Ok(it) }
        val result =
            enqueueTelegram(key, priority, chatId = null, consumer = consumer) { activePriority ->
                cache.get(key)?.let { return@enqueueTelegram Outcome.Ok(it) }
                val fetcher = customEmojiFetcher
                    ?: return@enqueueTelegram Outcome.Err("custom emoji fetcher not configured")
                val dest = File.createTempFile("emoji-", ".part", cacheRoot)
                try {
                    when (val fetched = fetcher(documentId, dest.absolutePath, activePriority)) {
                        is Outcome.Ok -> {
                            val file = File(fetched.value)
                            if (!file.exists()) {
                                Outcome.Err("custom emoji download produced no file")
                            } else {
                                runCatching {
                                    cache.putFile(
                                        key,
                                        file,
                                        chatId = null,
                                        kind = FileCache.KIND_STICKERS,
                                    )
                                }.fold(
                                    onSuccess = {
                                        markCached(key)
                                        Outcome.Ok(it)
                                    },
                                    onFailure = { Outcome.Err(it.message ?: "cache write failed") },
                                )
                            }
                        }

                        is Outcome.Err -> {
                            fetched
                        }
                    }
                } finally {
                    dest.delete()
                }
            }
        if (result is Outcome.Err) {
            AppLog.api("media", "err kind=Emoji key=emoji:$documentId ${result.message}")
        }
        return result
    }

    private suspend fun ensureLocalTelegramMedia(
        key: String,
        chatId: PeerId,
        messageId: Int,
        kind: MediaFetchKind,
        mediaKind: String? = null,
        priority: Int,
        name: String? = null,
        totalBytes: Long? = null,
        retain: Boolean = false,
        consumer: MediaConsumer? = null,
        scheduled: Boolean = false,
        legacyDisplayKey: String? = null,
    ): Outcome<File> {
        val storedChatId = if (mediaKind == "gif") null else chatId.value
        if (mediaKind == "gif") cache.attribute(key, chatId = null, kind = FileCache.KIND_GIFS)
        cache.get(key)?.let {
            PerfLog.event("cache_hit", kind.name.lowercase())
            AppLog.api(
                "media",
                "hit kind=${kind.name} key=$key bytes=${it.length()} chat=${chatId.value} id=$messageId",
            )
            return Outcome.Ok(if (retain) keptDownload(key) ?: it else it)
        }
        // Pending local ids must not become remote file slots. Scheduled callers use a separate encoded namespace.
        if (messageId < 0 && messageId != INSTANT_VIEW_MEDIA_MSG_ID && !scheduled) {
            AppLog.api("media", "skip local kind=${kind.name} key=$key id=$messageId")
            return Outcome.Err("local media")
        }
        PerfLog.event("cache_miss", kind.name.lowercase())
        val queuedAt = PerfLog.nowMs()
        val fetched = enqueueTelegram(
            key,
            priority,
            chatId = chatId.value,
            name = name,
            totalBytes = totalBytes,
            large = kind != MediaFetchKind.Thumb && isLargeDownload(totalBytes),
            thumbnail = kind == MediaFetchKind.Thumb,
            consumer = consumer,
        ) { activePriority ->
            val waited = PerfLog.nowMs() - queuedAt
            PerfLog.mark("queue_wait:${kind.name.lowercase()}", waited)
            cache.get(key)?.let { return@enqueueTelegram Outcome.Ok(it) }
            val dest = cache.fileFor(key)
            val part = File(dest.parentFile, "${dest.name}.part")
            if (isCancelled(key)) {
                return@enqueueTelegram Outcome.Err("cancelled")
            }
            setProgress(key, maxOf(progressBytes(key), part.length()))
            // The native side pipelines parts on one session; this is a cache wrapper.
            val fetcher = telegramFetcher
                ?: return@enqueueTelegram Outcome.Err("telegram media fetcher not configured")
            val destPath = part.absolutePath
            val reservation = try {
                totalBytes?.takeIf { it > 0L }?.let {
                    cache.reserve(key, it, optional = false)
                }
            } catch (error: FileCache.AdmissionException) {
                return@enqueueTelegram Outcome.Err(
                    error.message ?: "cache reservation refused",
                )
            }
            progressPaths[destPath] = key
            val fetched = try {
                fetcher.fetchExpectedMedia(
                    chatId = chatId,
                    messageId = messageId,
                    destPath = destPath,
                    kind = kind,
                    priority = activePriority,
                    // The on-disk rendition version is not part of the native media identity.
                    expectedCacheKey = legacyDisplayKey ?: key,
                )
            } finally {
                progressPaths.remove(destPath)
                cancelMarker(key).delete()
                cache.notePartial(key, part.length())
                reservation?.close()
            }
            currentCoroutineContext().ensureActive()
            when (fetched) {
                is Outcome.Ok -> {
                    val file = File(fetched.value)
                    if (isCancelled(key)) {
                        part.delete()
                        file.takeIf { it != part && it.exists() }?.delete()
                        clearProgress(key)
                        Outcome.Err("cancelled")
                    } else if (!file.exists()) {
                        part.delete()
                        clearProgress(key)
                        Outcome.Err("media download produced no file")
                    } else {
                        clearProgress(key)
                        legacyDisplayKey?.let(cache::acquire)?.use { legacy ->
                            val keepLegacy = hasBetterDisplayBounds(legacy.file, file)
                            currentCoroutineContext().ensureActive()
                            if (keepLegacy) {
                                file.delete()
                                part.delete()
                                cache.notePartial(key, 0)
                                return@enqueueTelegram Outcome.Err("no display size")
                            }
                        }
                        publishDownloadedFile(
                            key = key,
                            dest = dest,
                            source = file,
                            chatId = storedChatId,
                            mediaKind = mediaKind,
                        )
                    }
                }

                is Outcome.Err -> {
                    AppLog.api(
                        "media",
                        "err kind=${kind.name} key=$key ${fetched.message} chat=${chatId.value} id=$messageId",
                    )
                    val err = fetched.telegramError
                    val keepPartial =
                        err.kind == TelegramError.Kind.Flood || err.kind == TelegramError.Kind.Network
                    if (!keepPartial) {
                        part.delete()
                        clearProgress(key)
                    }
                    fetched
                }
            }
        }
        if (!retain || fetched !is Outcome.Ok) return fetched
        return Outcome.Ok(keptDownload(key) ?: fetched.value)
    }

    fun cancel(key: String) {
        scope.launch { cancelKey(key) }
    }

    /** Best-effort viewport cleanup; active callers release their own holds on cancellation. */
    fun cancelRunning(key: String): Job = scope.launch {
        telegramMutex.withLock {
            val job = telegramJobs[key] ?: return@withLock
            if (job.consumers > 0 || job.priority >= MediaPriority.USER) return@withLock
            cancelJobLocked(job)
        }
    }

    fun cancelChat(
        chatId: PeerId,
        belowPriority: Int = MediaPriority.USER,
        keepKeys: Set<String> = emptySet(),
    ) {
        scope.launch { cancelChatAwait(chatId, belowPriority, keepKeys) }
    }

    suspend fun cancelChatAwait(
        chatId: PeerId,
        belowPriority: Int = MediaPriority.USER,
        keepKeys: Set<String> = emptySet(),
    ) {
        val keys = telegramMutex.withLock {
            val matched = telegramJobs.values.filter { job ->
                job.chatId == chatId.value && job.priority < belowPriority && job.key !in keepKeys && !job.deferred.isCompleted
            }
            matched.forEach { cancelJobLocked(it) }
            matched.map { it.key }
        }
        if (keys.isNotEmpty()) PerfLog.event("cancel_chat", keys.size.toString())
    }

    fun shutdown() {
        telegramStopped = true
        cache.flushIndex()
        telegramWorkers.forEach { it.cancel() }
        telegramWorkers.clear()
        telegramWake.close()
        thumbWake.close()
        if (queueDelegate.isInitialized()) queue.shutdown()
        scope.cancel()
    }

    private suspend fun cancelKey(key: String) {
        telegramMutex.withLock {
            val job = telegramJobs[key] ?: run {
                markCancelled(key)
                return@withLock
            }
            cancelJobLocked(job)
        }
    }

    private fun markCancelled(key: String) {
        runCatching {
            cancelMarker(key).apply {
                parentFile?.mkdirs()
                writeText("")
            }
        }
    }

    private fun cancelJobLocked(job: TelegramJob) {
        markCancelled(job.key)
        job.cancelled = true
        job.preempted = false
        job.runner?.cancel()
        if (!job.running && !job.deferred.isCompleted) {
            job.deferred.complete(Outcome.Err("cancelled"))
            telegramJobs.remove(job.key)
            untrackUserDownloadLocked(job.key)
        }
    }

    private fun requeuePreemptedLocked(job: TelegramJob) {
        job.running = false
        job.preempted = false
        job.generation += 1
        job.sequence = telegramSequence++
        cancelMarker(job.key).delete()
        telegramPending += QueuedTelegram(job)
    }

    private suspend fun applyDownloadConsumerPriority(
        consumer: MediaConsumer,
        priority: Int,
        revision: Int,
    ) {
        telegramMutex.withLock {
            if (consumer.revision.get() != revision) return@withLock
            val job = telegramJobs[consumer.key]
            val slot = job?.slots?.get(consumer.id)
            if (job == null || slot == null) {
                pendingConsumerPriority[consumer.id] = priority
                return@withLock
            }
            if (slot.priority == priority) return@withLock
            slot.priority = priority
            publishConsumerPriorityLocked(job)
        }
        telegramWake.trySend(Unit)
        thumbWake.trySend(Unit)
    }

    private fun attachSlotLocked(
        job: TelegramJob,
        consumer: MediaConsumer,
        priority: Int,
        name: String?,
        totalBytes: Long?,
    ) {
        val requested = pendingConsumerPriority.remove(consumer.id) ?: priority
        val slot = job.slots[consumer.id]
        if (slot == null) {
            job.slots[consumer.id] = ConsumerSlot(requested, holds = 1)
        } else {
            slot.holds += 1
            slot.priority = requested
        }
        job.consumers++
        WorkOwnership.acquire("media:${job.key}", requested)
        if (requested >= MediaPriority.USER) {
            trackUserDownloadLocked(job.key, name, totalBytes)
        }
        publishConsumerPriorityLocked(job)
    }

    private fun attachAnonymousLocked(
        job: TelegramJob,
        priority: Int,
        name: String?,
        totalBytes: Long?,
    ) {
        job.anonymousPriorities.add(priority)
        job.consumers++
        WorkOwnership.acquire("media:${job.key}", priority)
        if (priority >= MediaPriority.USER) {
            trackUserDownloadLocked(job.key, name, totalBytes)
        }
        publishConsumerPriorityLocked(job)
    }

    private fun detachSlotLocked(
        job: TelegramJob,
        consumer: MediaConsumer,
    ) {
        val slot = job.slots[consumer.id] ?: return
        slot.holds -= 1
        job.consumers--
        WorkOwnership.release("media:${job.key}")
        if (slot.holds > 0) return
        job.slots.remove(consumer.id)
        pendingConsumerPriority.remove(consumer.id)
        publishConsumerPriorityLocked(job)
    }

    /** Retargets the existing job. A queue refresh is the same [TelegramJob], not a second transfer. */
    private fun publishConsumerPriorityLocked(job: TelegramJob) {
        if (job.cancelled || job.deferred.isCompleted) return
        val next = maxOf(
            job.anonymousPriorities.maxOrNull() ?: Int.MIN_VALUE,
            job.slots.values.maxOfOrNull { it.priority } ?: Int.MIN_VALUE,
            if (isUserDownload(job.key)) MediaPriority.USER else Int.MIN_VALUE,
        )
        if (next == Int.MIN_VALUE || next == job.priority) return
        val increased = next > job.priority
        job.priority = next
        if (!job.running) {
            job.generation += 1
            job.sequence = telegramSequence++
            telegramPending += QueuedTelegram(job)
        } else if (increased) {
            val dest = cache.fileFor(job.key)
            telegramFetcher?.promote(
                File(dest.parentFile, "${dest.name}.part").absolutePath,
                next,
            )
        }
        if (increased) WorkOwnership.promote("media:${job.key}", next)
    }

    private suspend fun enqueueTelegram(
        key: String,
        priority: Int,
        chatId: Long? = null,
        name: String? = null,
        totalBytes: Long? = null,
        large: Boolean = false,
        thumbnail: Boolean = false,
        consumer: MediaConsumer? = null,
        acceptCached: (File) -> Boolean = { true },
        work: suspend (Int) -> Outcome<File>,
    ): Outcome<File> {
        cache.get(key)?.takeIf(acceptCached)?.let { return Outcome.Ok(it) }
        val bound = consumer?.takeIf { it.key == key }
        val job = telegramMutex.withLock {
            check(!telegramStopped) { "download queue is shut down" }
            val existing = telegramJobs[key]
            val attached = if (existing != null) {
                if (existing.chatId == null && chatId != null) existing.chatId = chatId
                if (large) existing.large = true
                if (bound == null) {
                    attachAnonymousLocked(existing, priority, name, totalBytes)
                } else {
                    attachSlotLocked(existing, bound, priority, name, totalBytes)
                }
                existing
            } else {
                cancelMarker(key).delete()
                val created = TelegramJob(
                    key = key,
                    chatId = chatId,
                    priority = priority,
                    sequence = telegramSequence++,
                    large = large,
                    thumbnail = thumbnail,
                    work = work,
                    deferred = CompletableDeferred(scope.coroutineContext[Job]),
                )
                telegramJobs[key] = created
                telegramPending += QueuedTelegram(created)
                if (bound == null) {
                    attachAnonymousLocked(created, priority, name, totalBytes)
                } else {
                    attachSlotLocked(created, bound, priority, name, totalBytes)
                }
                created
            }
            ensureTelegramWorkerLocked()
            attached
        }
        telegramWake.trySend(Unit)
        thumbWake.trySend(Unit)
        try {
            if (job.cancelled) {
                job.deferred.await()
                return enqueueTelegram(
                    key,
                    priority,
                    chatId = chatId,
                    name = name,
                    totalBytes = totalBytes,
                    large = large,
                    thumbnail = thumbnail,
                    consumer = bound,
                    acceptCached = acceptCached,
                    work = work,
                )
            }
            return job.deferred.await()
        } finally {
            withContext(NonCancellable) {
                telegramMutex.withLock {
                    if (bound == null) {
                        job.anonymousPriorities.remove(priority)
                        job.consumers--
                        WorkOwnership.release("media:${job.key}")
                        publishConsumerPriorityLocked(job)
                    } else {
                        detachSlotLocked(job, bound)
                    }
                    if (job.consumers == 0) {
                        WorkOwnership.finish("media:${job.key}")
                    }
                    if (job.consumers == 0 && job.priority < MediaPriority.USER && !job.deferred.isCompleted) {
                        cancelJobLocked(job)
                    }
                }
            }
        }
    }

    private fun ensureTelegramWorkerLocked() {
        if (telegramStopped) return
        val fullWorkers = maxConcurrentTelegram.coerceAtLeast(1)
        val want = fullWorkers + THUMB_WORKERS
        while (telegramWorkers.size < want) {
            val thumbnail = telegramWorkers.size >= fullWorkers
            telegramWorkers += scope.launch(Dispatchers.IO) {
                while (isActive && !telegramStopped) {
                    takeNextTelegram(thumbnail)?.let { runTelegram(it) }
                }
            }
        }
    }

    private suspend fun takeNextTelegram(thumbnail: Boolean): TelegramJob? {
        while (!telegramStopped) {
            val next = telegramMutex.withLock { pollTelegramLocked(thumbnail) }
            if (next != null) return next
            (if (thumbnail) thumbWake else telegramWake).receiveCatching()
        }
        return null
    }

    private fun pollTelegramLocked(thumbnail: Boolean): TelegramJob? {
        val largeSlotsFull = runningLargeLocked() >= MAX_LARGE_PIPELINES
        val skipped = ArrayList<QueuedTelegram>()
        try {
            while (true) {
                val queued = telegramPending.poll() ?: return null
                val job = queued.job
                if (queued.generation != job.generation) continue
                if (job.cancelled || job.running || job.deferred.isCompleted) continue
                if (job.thumbnail != thumbnail || (job.large && largeSlotsFull)) {
                    skipped += queued
                    continue
                }
                job.running = true
                return job
            }
        } finally {
            skipped.forEach { telegramPending += it }
        }
    }

    private suspend fun runTelegram(job: TelegramJob) {
        val result = perfOp("telegram_job") {
            try {
                coroutineScope {
                    val runner = async(
                        PerfLog.OpTrace(job.opId.takeIf { it != 0L } ?: PerfLog.newOpId()),
                        start = CoroutineStart.LAZY,
                    ) { job.work(job.priority) }
                    telegramMutex.withLock {
                        job.runner = runner
                        if (job.cancelled || isCancelled(job.key)) runner.cancel()
                    }
                    try {
                        runner.await()
                    } finally {
                        job.runner = null
                    }
                }
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) throw e
                Outcome.Err("cancelled", e)
            } catch (e: Exception) {
                Outcome.Err(e.message ?: "media download failed", e)
            }
        }
        val requeued = telegramMutex.withLock {
            if (job.preempted && !job.cancelled && result !is Outcome.Ok) {
                requeuePreemptedLocked(job)
                true
            } else {
                telegramJobs.remove(job.key)
                untrackUserDownloadLocked(job.key)
                job.deferred.complete(if (job.cancelled) Outcome.Err("cancelled") else result)
                false
            }
        }
        if (requeued) AppLog.api("media", "requeue key=${job.key} pri=${job.priority}")
        telegramWake.trySend(Unit)
        thumbWake.trySend(Unit)
    }

    private fun runningLargeLocked(): Int = telegramJobs.values.count { it.running && it.large }

    /**
     * File the explicit-download UI opens. It lives outside the evictable cache,
     * and the cache entry is pinned so a clear cannot delete a path still in use.
     */
    private fun keptDownload(key: String): File? {
        val source = cache.get(key) ?: return null
        cache.pin(key)
        val parent = streamRoot.parentFile ?: return source
        var temporary: File? = null
        return try {
            val dir = File(parent, "downloads").apply { mkdirs() }
            val dest = File(dir, cache.fileFor(key).name)
            if (dest.isFile && dest.length() == source.length()) return dest
            val staged = File.createTempFile("retained-", ".part", dir)
            temporary = staged
            source.inputStream().use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            }
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                Files.move(
                    staged.toPath(),
                    dest.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } else {
                check(staged.renameTo(dest)) { "retained download replacement failed" }
            }
            dest
        } catch (_: Exception) {
            source
        } finally {
            temporary?.delete()
        }
    }

    private fun trackUserDownloadLocked(
        key: String,
        name: String?,
        totalBytes: Long?,
    ) {
        val label = name?.trim().orEmpty().ifEmpty { key }
        userDownloadsState.update { current ->
            current + (key to UserDownload(key = key, name = label, totalBytes = totalBytes))
        }
    }

    private fun untrackUserDownloadLocked(key: String) {
        userDownloadsState.update { current ->
            if (key in current) current - key else current
        }
    }

    private fun partFile(key: String): File {
        val dest = cache.fileFor(key)
        return File(dest.parentFile, "${dest.name}.part")
    }

    private fun cancelMarker(key: String): File = File(partFile(key).path + ".cancel")

    private fun isCancelled(key: String): Boolean = cancelMarker(key).exists()

    private fun hasBetterDisplayBounds(
        legacy: File,
        candidate: File,
    ): Boolean {
        fun bounds(file: File) = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, this)
        }

        val old = bounds(legacy)
        if (old.outWidth <= 0 || old.outHeight <= 0) return false
        val new = bounds(candidate)
        return minOf(new.outWidth, new.outHeight) < minOf(
            old.outWidth,
            old.outHeight
        ) || maxOf(new.outWidth, new.outHeight) < maxOf(old.outWidth, old.outHeight)
    }

    private fun publishDownloadedFile(
        key: String,
        dest: File,
        source: File,
        chatId: Long?,
        mediaKind: String?,
    ): Outcome<File> = runCatching {
        cache.putFile(
            key,
            source,
            chatId = chatId,
            kind = FileCache.inferKind(key, mediaKind),
        )
    }.fold(
        onSuccess = {
            if (source.canonicalFile != dest.canonicalFile) source.delete()
            markCached(key)
            Outcome.Ok(it)
        },
        onFailure = { Outcome.Err(it.message ?: "cache write failed") },
    )

    fun onNativeProgress(
        destPath: String,
        downloaded: Long,
    ) {
        if (downloaded <= 0L) return
        val key = progressPaths[destPath] ?: progressPaths.entries.firstOrNull {
                destPath.contains(
                    File(it.key).name,
                )
            }?.value ?: return
        setProgress(key, maxOf(progressBytes(key), downloaded))
    }

    private fun setProgress(
        key: String,
        bytes: Long,
    ) {
        progress.update { current ->
            if (current[key] == bytes) current else current + (key to bytes)
        }
    }

    private fun clearProgress(key: String) {
        progress.update { current ->
            if (key in current) current - key else current
        }
    }

    private class ConsumerSlot(
        var priority: Int,
        var holds: Int,
    )

    private class TelegramJob(
        val key: String,
        var chatId: Long?,
        @Volatile var priority: Int,
        var sequence: Long,
        val work: suspend (Int) -> Outcome<File>,
        val opId: Long = PerfLog.currentOpId(),
        var large: Boolean = false,
        val thumbnail: Boolean = false,
        val deferred: CompletableDeferred<Outcome<File>>,
        var generation: Int = 0,
        var running: Boolean = false,
        var consumers: Int = 0,
        val anonymousPriorities: MutableList<Int> = mutableListOf(),
        val slots: LinkedHashMap<Long, ConsumerSlot> = LinkedHashMap(),
        @Volatile var cancelled: Boolean = false,
        @Volatile var preempted: Boolean = false,
        @Volatile var runner: Job? = null,
    )

    private class QueuedTelegram(
        val job: TelegramJob,
    ) : Comparable<QueuedTelegram> {
        val generation: Int = job.generation
        private val priority: Int = job.priority
        private val sequence: Long = job.sequence

        override fun compareTo(other: QueuedTelegram): Int {
            val byPriority = other.priority.compareTo(priority)
            if (byPriority != 0) return byPriority
            return sequence.compareTo(other.sequence)
        }
    }

    companion object {
        const val INLINE_THUMB_CAP = 128
        const val THUMB_WORKERS = 2
        const val TELEGRAM_WORKERS = 5
        const val MAX_LARGE_PIPELINES = 2
        const val STREAM_PART = 512L * 1024
        const val STREAM_PREFIX_BYTES = 2L * 1024 * 1024

        @Volatile
        var progressSink: ((String, Long) -> Unit)? = null

        fun isVideoAvatarKey(key: String): Boolean = key.endsWith(":video")
    }
}

internal fun dropEldest(
    map: MutableMap<String, ByteArray>,
    cap: Int,
) {
    while (map.size > cap) {
        val eldest = map.keys.firstOrNull() ?: return
        map.remove(eldest)
    }
}
