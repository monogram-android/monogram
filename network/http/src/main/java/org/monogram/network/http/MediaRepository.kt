package org.monogram.network.http

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.runningFold
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
import java.io.File
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

fun photoDisplayCacheKey(mediaCacheKey: String): String = "$mediaCacheKey:display"

fun peerPhotoSmallCacheKey(photoCacheKey: String): String =
    if (photoCacheKey.endsWith(":small") || photoCacheKey.endsWith(":video")) {
        photoCacheKey
    } else {
        "$photoCacheKey:small"
    }

internal fun streamPrefixPart(
    directory: File,
    cacheKey: String,
    offset: Long
): File =
    FileCache(directory, maintainOnInit = false).fileFor(streamPartKey(cacheKey, offset))

internal fun streamPartKey(cacheKey: String, offset: Long): String = "$cacheKey:stream:v2:$offset"

internal fun streamMediaKey(message: Message): String? = message.mediaCacheKey?.let {
    "$it:chat:${message.id.chatId.value}:message:${message.id.id}:size:${message.fileSize ?: 0}"
}

/** A prefix part the player can read without fetching again. */
internal fun reusableStreamPrefix(
    file: File?,
    offset: Long = 0,
    size: Long? = null
): File? {
    val expected = size?.takeIf { it > 0 }?.let { minOf(512L * 1024, it - offset) } ?: 512L * 1024
    return file?.takeIf { expected > 0 && it.isFile && it.length() == expected }
}

fun mediaThumbCacheKey(mediaCacheKey: String): String =
    if (mediaCacheKey.endsWith(":thumb")) mediaCacheKey else "$mediaCacheKey:thumb"

data class UserDownload(
    val key: String,
    val name: String,
    val totalBytes: Long? = null,
)

private val NO_SYNTHETIC_THUMB_KINDS = setOf("document", "audio", "voice")

fun interface TelegramMediaFetcher {
    suspend fun fetchMessageMedia(
        chatId: PeerId,
        messageId: Int,
        destPath: String,
        kind: MediaFetchKind,
        priority: Int,
    ): Outcome<String>

    /** A visible consumer joined the transfer writing [destPath]. */
    fun promote(destPath: String, priority: Int) = Unit
}

fun interface TelegramInlineThumbPeek {
    fun peek(chatId: PeerId, messageId: Int): ByteArray?
}

fun interface TelegramPeerInlineThumbPeek {
    fun peek(peerId: PeerId): ByteArray?
}

class MediaRepository(
    cacheRoot: File,
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
    fun createMessageDataSourceFactory(message: Message): androidx.media3.datasource.DataSource.Factory =
        TelegramVideoDataSource.Factory(message, streamRoot, telegramChunkFetcher, cache)

    @OptIn(UnstableApi::class)
    fun createCachedDataSourceFactory(key: String): androidx.media3.datasource.DataSource.Factory =
        androidx.media3.datasource.DataSource.Factory { LeasedFileDataSource(cache, key) }
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
    private val telegramWorkers = mutableListOf<Job>()
    private var telegramSequence = 0L
    @Volatile private var telegramStopped = false
    private val progressPaths = ConcurrentHashMap<String, String>()

    val downloadProgress: StateFlow<Map<String, Long>> = progress.asStateFlow()
    private val userDownloadsState = MutableStateFlow<Map<String, UserDownload>>(emptyMap())
    val userDownloads: StateFlow<Map<String, UserDownload>> = userDownloadsState.asStateFlow()
    val cacheGeneration: StateFlow<Long> = generation.asStateFlow()

    fun isUserDownload(key: String): Boolean = key in userDownloadsState.value

    fun protectCacheKeys(owner: String, keys: Collection<String>) {
        cache.setProtectedKeys(owner, keys)
    }
    private val inlineThumbs = ConcurrentHashMap<String, ByteArray>()

    fun inlineThumbJpeg(message: Message): ByteArray? {
        val key = message.thumbCacheKey
            ?: message.mediaCacheKey?.let(::mediaThumbCacheKey)
            ?: return null
        inlineThumbs[key]?.let { return it }
        val bytes = inlineThumbPeek?.peek(message.id.chatId, message.id.id) ?: return null
        if (bytes.isNotEmpty()) {
            inlineThumbs[key] = bytes
            PerfLog.event("stripped_inline")
        }
        return bytes.takeIf { it.isNotEmpty() }
    }

    /** Stripped or cached avatar JPEG indexed at `(peerId, messageId = 0)`. No download. */
    fun peerInlineThumbJpeg(peerId: PeerId): ByteArray? {
        val key = "${avatarPeerKey(peerId)}:thumb"
        inlineThumbs[key]?.let { return it }
        val bytes = peerInlineThumbPeek?.peek(peerId) ?: return null
        if (bytes.isNotEmpty()) {
            inlineThumbs[key] = bytes
            PerfLog.event("stripped_peer")
        }
        return bytes.takeIf { it.isNotEmpty() }
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
    fun cacheGeneration(keys: Iterable<String>): Flow<Long> {
        val observed = keys.asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .map(::observeCacheGeneration)
            .plus(cacheClearedGeneration)
            .map { it.drop(1) }
            .toList()
        return merge(*observed.toTypedArray()).runningFold(0L) { version, _ -> version + 1 }
    }

    fun cacheGeneration(key: String): Flow<Long> = cacheGeneration(listOf(key))

    private fun observeCacheGeneration(key: String): Flow<Long> = flow {
        val entry = keyedGenerations.compute(key) { _, current ->
            (current ?: KeyedGeneration()).also { it.collectors.incrementAndGet() }
        }!!
        try {
            emitAll(entry.generation)
        } finally {
            keyedGenerations.computeIfPresent(key) { _, current ->
                if (current === entry && entry.collectors.decrementAndGet() == 0) null else current
            }
        }
    }

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

    fun cachedAvatar(peerId: PeerId): File? = cache.get(avatarPeerKey(peerId))

    private fun avatarPeerKey(peerId: PeerId): String = "avatar:${peerId.value}"

    private fun rememberPeerAvatar(peerId: PeerId, file: File) {
        val alias = avatarPeerKey(peerId)
        val existing = cache.get(alias)
        if (existing != null) {
            if (existing.canonicalFile == file.canonicalFile) return
            runCatching { cache.putFile(alias, file, peerId.value, FileCache.KIND_PHOTOS) }
            return
        }
        runCatching {
            cache.putFile(alias, file, peerId.value, FileCache.KIND_PHOTOS)
        }.onSuccess { markCached(alias) }
    }

    fun clearCache(): Long {
        val freed = cache.clear()
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
    ): Outcome<File> {
        val key = message.mediaCacheKey
            ?: return Outcome.Err("message has no media key")
        return ensureLocalTelegramMedia(
            key = key,
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
        val key = message.mediaCacheKey?.let { photoDisplayCacheKey(it) }
            ?: return Outcome.Err("message has no media key")
        return ensureLocalTelegramMedia(
            key = key,
            chatId = message.id.chatId,
            messageId = message.id.id,
            kind = MediaFetchKind.Display,
            mediaKind = message.mediaKind,
            priority = maxOf(priority, MediaPriority.DISPLAY),
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
        if (!streamRoot.isDirectory && !streamRoot.mkdirs()) {
            return Outcome.Err("stream prefix directory")
        }
        var last: File? = null
        var offset = 0L
        while (offset < limit) {
            val dest = streamPrefixPart(streamRoot, key, offset)
            if (reusableStreamPrefix(dest, offset, message.fileSize) != null) {
                last = dest
                offset += STREAM_PART
                continue
            }
            val temporary = File.createTempFile("stream-$offset-", ".part", streamRoot)
            try {
                when (val result = fetcher.fetch(
                    message.id.chatId,
                    message.id.id,
                    temporary.absolutePath,
                    offset
                )) {
                    is Outcome.Err -> {
                        temporary.delete()
                        return last?.let { Outcome.Ok(it) } ?: result
                    }

                    is Outcome.Ok -> {
                        val fetched = File(result.value)
                        val bytes = fetched.length()
                        if (fetched.canonicalFile != temporary.canonicalFile ||
                            bytes <= 0L || bytes > STREAM_PART ||
                            (message.fileSize?.takeIf { it > 0 } != null &&
                                    reusableStreamPrefix(fetched, offset, message.fileSize) == null)
                        ) {
                            return last?.let { Outcome.Ok(it) }
                                ?: Outcome.Err("invalid stream prefix")
                        }
                        java.io.RandomAccessFile(fetched, "rw").use { it.fd.sync() }
                        val file = try {
                            cache.putFile(
                                streamPartKey(key, offset),
                                fetched,
                                message.id.chatId.value,
                                FileCache.KIND_VIDEOS,
                            )
                        } catch (error: Exception) {
                            return last?.let { Outcome.Ok(it) }
                                ?: Outcome.Err(error.message ?: "stream prefix publish", error)
                        }
                        last = file
                        if (bytes < STREAM_PART) return Outcome.Ok(file)
                    }
                }
            } finally {
                temporary.delete()
            }
            offset += STREAM_PART
        }
        if (last != null && message.mediaKind == "video") {
            try {
                prefetchMp4Metadata(message, key, fetcher)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                PerfLog.event("stream_metadata", "result=fallback")
            }
        }
        return last?.let { Outcome.Ok(it) } ?: Outcome.Err("empty stream prefix")
    }

    private suspend fun prefetchMp4Metadata(
        message: Message,
        key: String,
        fetcher: TelegramChunkFetcher
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
            val dest = streamPrefixPart(streamRoot, key, aligned)
            reusableStreamPrefix(dest, aligned, size)?.let { return it }
            val charge = minOf(STREAM_PART, size - aligned)
            if (charge <= 0 || charge > remaining) return null
            remaining -= charge
            val temporary = File.createTempFile("metadata-", ".part", streamRoot)
            try {
                when (val result = fetcher.fetch(
                    message.id.chatId,
                    message.id.id,
                    temporary.absolutePath,
                    aligned
                )) {
                    is Outcome.Err -> return null
                    is Outcome.Ok -> if (File(result.value).canonicalFile != temporary.canonicalFile) return null
                }
                if (reusableStreamPrefix(temporary, aligned, size) == null) return null
                java.io.RandomAccessFile(temporary, "rw").use { it.fd.sync() }
                return runCatching {
                    cache.putFile(
                        partKey,
                        temporary,
                        message.id.chatId.value,
                        FileCache.KIND_VIDEOS
                    )
                }.getOrNull()
            } finally {
                temporary.delete()
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
                        file.length() - (position % STREAM_PART)
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
                        complete = false; break
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
    ): Outcome<File> {
        val distinctThumb = message.thumbCacheKey?.takeUnless { it == message.mediaCacheKey }
        if (distinctThumb == null && message.mediaKind in NO_SYNTHETIC_THUMB_KINDS) {
            return Outcome.Err("no downloadable thumb")
        }
        val key = distinctThumb
            ?: message.mediaCacheKey?.let(::mediaThumbCacheKey)
            ?: return Outcome.Err("message has no media key")
        return ensureLocalTelegramMedia(
            key = key,
            chatId = message.id.chatId,
            messageId = message.id.id,
            kind = MediaFetchKind.Thumb,
            mediaKind = message.mediaKind,
            priority = priority,
        )
    }

    /** Profile avatars are indexed as `(peerId, messageId=0)` in the native media map. */
    suspend fun ensureLocalAvatar(
        peerId: PeerId,
        cacheKey: String,
        priority: Int = MediaPriority.DEFAULT,
    ): Outcome<File> {
        if (isVideoAvatarKey(cacheKey)) {
            return fetchFullAvatar(peerId, cacheKey, priority)
        }
        cache.get(cacheKey)?.let { file ->
            rememberPeerAvatar(peerId, file)
            return Outcome.Ok(file)
        }
        val smallKey = peerPhotoSmallCacheKey(cacheKey)
        cache.get(smallKey)?.let { file ->
            scheduleFullAvatar(peerId, cacheKey)
            return Outcome.Ok(file)
        }
        cachedAvatar(peerId)?.let { file ->
            scheduleFullAvatar(peerId, cacheKey)
            return Outcome.Ok(file)
        }
        when (
            val small = ensureLocalTelegramMedia(
                key = smallKey,
                chatId = peerId,
                messageId = 0,
                kind = MediaFetchKind.Display,
                priority = priority,
            )
        ) {
            is Outcome.Ok -> {
                scheduleFullAvatar(peerId, cacheKey)
                return small
            }

            is Outcome.Err -> Unit
        }
        return fetchFullAvatar(peerId, cacheKey, priority)
    }

    private suspend fun fetchFullAvatar(
        peerId: PeerId,
        cacheKey: String,
        priority: Int,
    ): Outcome<File> = when (
        val fetched = ensureLocalTelegramMedia(
            key = cacheKey,
            chatId = peerId,
            messageId = 0,
            kind = MediaFetchKind.Full,
            priority = priority,
        )
    ) {
        is Outcome.Ok -> {
            rememberPeerAvatar(peerId, fetched.value)
            fetched
        }

        is Outcome.Err -> {
            if (isVideoAvatarKey(cacheKey)) fetched
            else cachedAvatar(peerId)?.let { Outcome.Ok(it) } ?: fetched
        }
    }

    private fun scheduleFullAvatar(peerId: PeerId, cacheKey: String) {
        if (isVideoAvatarKey(cacheKey) || cache.get(cacheKey) != null) return
        scope.launch {
            if (cache.get(cacheKey) != null) return@launch
            val fetched = ensureLocalTelegramMedia(
                key = cacheKey,
                chatId = peerId,
                messageId = 0,
                kind = MediaFetchKind.Full,
                priority = MediaPriority.IDLE,
            )
            if (fetched is Outcome.Ok) rememberPeerAvatar(peerId, fetched.value)
        }
    }

    suspend fun ensureIndexedMedia(
        peerId: PeerId,
        messageId: Int,
        cacheKey: String,
        thumb: Boolean = false,
        priority: Int = MediaPriority.DEFAULT,
        mediaKind: String? = null,
    ): Outcome<File> = ensureLocalTelegramMedia(
        key = cacheKey,
        chatId = peerId,
        messageId = messageId,
        kind = if (thumb) MediaFetchKind.Thumb else MediaFetchKind.Full,
        mediaKind = mediaKind,
        priority = priority,
    )

    suspend fun ensureCustomEmoji(
        documentId: Long,
        priority: Int = MediaPriority.DEFAULT,
    ): Outcome<File> {
        val key = "emoji:$documentId"
        cache.get(key)?.let { return Outcome.Ok(it) }
        val result = enqueueTelegram(key, priority, chatId = null) { activePriority ->
            cache.get(key)?.let { return@enqueueTelegram Outcome.Ok(it) }
            val dest = cache.fileFor(key)
            val fetcher = customEmojiFetcher
                ?: return@enqueueTelegram Outcome.Err("custom emoji fetcher not configured")
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
                is Outcome.Err -> fetched
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
        // Negative ids other than instant-view media are not Telegram file slots.
        // Page photos and documents are indexed as (id, -1) and must still download.
        if (messageId < 0 && messageId != INSTANT_VIEW_MEDIA_MSG_ID) {
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
            progressPaths[destPath] = key
            val reservation = totalBytes?.takeIf { it > 0L }
                ?.let { runCatching { cache.reserve(key, it, optional = false) }.getOrNull() }
            val fetched = try {
                fetcher.fetchMessageMedia(
                    chatId = chatId,
                    messageId = messageId,
                    destPath = destPath,
                    kind = kind,
                    priority = activePriority,
                )
            } finally {
                progressPaths.remove(destPath)
                cancelMarker(key).delete()
                cache.notePartial(key, part.length())
                reservation?.close()
            }
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
                    val keepPartial = err.kind == TelegramError.Kind.Flood ||
                        err.kind == TelegramError.Kind.Network
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

    /** Cancel an in-flight job for this key only. No-op if nothing is queued. */
    fun cancelRunning(key: String) {
        scope.launch {
            telegramMutex.withLock {
                val job = telegramJobs[key] ?: return@withLock
                if (job.priority >= MediaPriority.USER) return@withLock
                cancelJobLocked(job)
            }
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
                job.chatId == chatId.value &&
                    job.priority < belowPriority &&
                    job.key !in keepKeys &&
                    !job.deferred.isCompleted
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
        runCatching { cancelMarker(key).apply { parentFile?.mkdirs(); writeText("") } }
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

    private fun hasPendingAtLeastLocked(minPriority: Int, thumbnail: Boolean? = null): Boolean =
        telegramPending.any { queued ->
            val job = queued.job
            queued.generation == job.generation &&
                !job.running &&
                !job.cancelled &&
                !job.deferred.isCompleted &&
                    job.priority >= minPriority && (thumbnail == null || job.thumbnail == thumbnail)
        }

    private suspend fun enqueueTelegram(
        key: String,
        priority: Int,
        chatId: Long? = null,
        name: String? = null,
        totalBytes: Long? = null,
        large: Boolean = false,
        thumbnail: Boolean = false,
        work: suspend (Int) -> Outcome<File>,
    ): Outcome<File> {
        cache.get(key)?.let { return Outcome.Ok(it) }
        val job = telegramMutex.withLock {
            check(!telegramStopped) { "download queue is shut down" }
            val existing = telegramJobs[key]
            if (existing != null) {
                if (existing.chatId == null && chatId != null) existing.chatId = chatId
                if (large) existing.large = true
                if (priority > existing.priority) {
                    existing.priority = priority
                    if (!existing.cancelled && !existing.running) {
                        existing.generation += 1
                        existing.sequence = telegramSequence++
                        telegramPending += QueuedTelegram(existing)
                    } else if (existing.running && !existing.cancelled) {
                        val dest = cache.fileFor(key)
                        telegramFetcher?.promote(
                            File(
                                dest.parentFile,
                                "${dest.name}.part"
                            ).absolutePath, priority
                        )
                    }
                }
                if (priority >= MediaPriority.USER) {
                    trackUserDownloadLocked(key, name, totalBytes)
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
                )
                telegramJobs[key] = created
                telegramPending += QueuedTelegram(created)
                if (priority >= MediaPriority.USER) {
                    trackUserDownloadLocked(key, name, totalBytes)
                }
                created
            }.also {
                it.consumers++
                WorkOwnership.acquire("media:${it.key}", it.priority)
                ensureTelegramWorkerLocked()
            }
        }
        telegramWake.trySend(Unit)
        thumbWake.trySend(Unit)
        try {
            if (job.cancelled) {
                job.deferred.await()
                return enqueueTelegram(
                    key, priority, chatId = chatId, name = name, totalBytes = totalBytes,
                    large = large, thumbnail = thumbnail, work = work,
                )
            }
            return job.deferred.await()
        } finally {
            withContext(NonCancellable) {
                telegramMutex.withLock {
                    job.consumers--
                    WorkOwnership.release("media:${job.key}")
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
        val holdIdle = hasPendingAtLeastLocked(MediaPriority.IDLE + 1, thumbnail)
        val holdDefault = hasPendingAtLeastLocked(MediaPriority.DISPLAY, thumbnail)
        val skipped = ArrayList<QueuedTelegram>()
        try {
            while (true) {
                val queued = telegramPending.poll() ?: return null
                val job = queued.job
                if (queued.generation != job.generation) continue
                if (job.cancelled || job.running || job.deferred.isCompleted) continue
                val hold = when {
                    job.thumbnail != thumbnail -> true
                    job.large && runningLargeLocked() >= MAX_LARGE_PIPELINES -> true
                    holdDefault && job.priority < MediaPriority.DISPLAY -> true
                    holdIdle && job.priority <= MediaPriority.IDLE -> true
                    else -> false
                }
                if (hold) {
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
        if (job.cancelled || isCancelled(job.key)) {
            if (!job.deferred.isCompleted) job.deferred.complete(Outcome.Err("cancelled"))
            telegramMutex.withLock {
                if (telegramJobs[job.key] === job) telegramJobs.remove(job.key)
                untrackUserDownloadLocked(job.key)
            }
            return
        }
        val result = perfOp("telegram_job") {
            try {
                coroutineScope {
                    val runner =
                        async(PerfLog.OpTrace(job.opId.takeIf { it != 0L } ?: PerfLog.newOpId()),
                            start = CoroutineStart.LAZY) { job.work(job.priority) }
                    job.runner = runner
                    try {
                        runner.start()
                        runner.await()
                    } finally {
                        job.runner = null
                    }
                }
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) throw e
                Outcome.Err("cancelled")
            } catch (e: Exception) {
                Outcome.Err(e.message ?: "media download failed", e)
            }
        }
        val requeued = telegramMutex.withLock {
            if (job.preempted && !job.cancelled && result !is Outcome.Ok) {
                requeuePreemptedLocked(job)
                true
            } else {
                false
            }
        }
        if (requeued) {
            AppLog.api("media", "requeue key=${job.key} pri=${job.priority}")
            telegramWake.trySend(Unit)
            thumbWake.trySend(Unit)
            return
        }
        if (!job.deferred.isCompleted) {
            job.deferred.complete(result)
        }
        telegramMutex.withLock {
            if (telegramJobs[job.key] === job) telegramJobs.remove(job.key)
            untrackUserDownloadLocked(job.key)
        }
        telegramWake.trySend(Unit)
        thumbWake.trySend(Unit)
    }

    private fun runningLargeLocked(): Int =
        telegramJobs.values.count { it.running && it.large }

    /**
     * File the explicit-download UI opens. It lives outside the evictable cache,
     * and the cache entry is pinned so a clear cannot delete a path still in use.
     */
    private fun keptDownload(key: String): File? {
        val source = cache.get(key) ?: return null
        cache.pin(key)
        val parent = streamRoot.parentFile ?: return source
        val dir = File(parent, "downloads").apply { mkdirs() }
        val safe = key.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "file" }
        val dest = File(dir, safe)
        val copied = runCatching {
            source.inputStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }.isSuccess
        return if (copied) dest else source
    }

    private fun trackUserDownloadLocked(key: String, name: String?, totalBytes: Long?) {
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

    private fun publishDownloadedFile(
        key: String,
        dest: File,
        source: File,
        chatId: Long?,
        mediaKind: String?,
    ): Outcome<File> {
        return runCatching {
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
    }

    fun onNativeProgress(destPath: String, downloaded: Long) {
        if (downloaded <= 0L) return
        val key = progressPaths[destPath]
            ?: progressPaths.entries.firstOrNull { destPath.contains(File(it.key).name) }?.value
            ?: return
        setProgress(key, maxOf(progressBytes(key), downloaded))
    }

    private fun setProgress(key: String, bytes: Long) {
        progress.update { current ->
            if (current[key] == bytes) current else current + (key to bytes)
        }
    }

    private fun clearProgress(key: String) {
        progress.update { current ->
            if (key in current) current - key else current
        }
    }

    private class TelegramJob(
        val key: String,
        var chatId: Long?,
        var priority: Int,
        var sequence: Long,
        val work: suspend (Int) -> Outcome<File>,
        val opId: Long = PerfLog.currentOpId(),
        var large: Boolean = false,
        val thumbnail: Boolean = false,
        val deferred: CompletableDeferred<Outcome<File>> = CompletableDeferred(),
        var generation: Int = 0,
        var running: Boolean = false,
        var consumers: Int = 0,
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
