package org.monogram.network.http

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

data class CacheRecord(
    val key: String,
    val chatId: Long?,
    val kind: String,
    val bytes: Long,
    /** Explicit user download: never evicted or cleared as disposable cache. */
    val pinned: Boolean = false,
)

/**
 * Mutually exclusive byte buckets. A byte is counted in exactly one of them:
 * evictable completed files, protected (leased, viewport-protected or pinned)
 * files, partial staging files, or reserved-but-unwritten space.
 */
data class StorageAccounting(
    val completedBytes: Long,
    val protectedBytes: Long,
    val partialBytes: Long,
    val reservedBytes: Long,
) {
    val totalBytes: Long get() = completedBytes + protectedBytes + partialBytes + reservedBytes
}

class FileCache(
    private val root: File,
    maxBytes: Long = 256L * 1024L * 1024L,
    private val maintainOnInit: Boolean = true,
    private val nowMs: () -> Long = System::currentTimeMillis,
    enforceSize: Boolean = true,
) {
    private var maxBytes: Long = maxBytes
    private var enforceSize: Boolean = enforceSize
    enum class AdmissionFailure { Capacity, Storage, InUse }
    class AdmissionException(val reason: AdmissionFailure) : IOException(
        when (reason) {
            AdmissionFailure.Capacity -> "media exceeds cache capacity"
            AdmissionFailure.Storage -> "insufficient media cache storage"
            AdmissionFailure.InUse -> "cache entry is in use"
        }
    )
    private val indexFile = File(root, INDEX_NAME)
    private val records = LinkedHashMap<String, CacheRecord>()
    private val recordsLock = Any()
    private var indexLoaded = false
    private val protectedKeys = HashMap<String, Set<String>>()
    private val leases = HashMap<String, Int>()
    private val pendingDeletes = HashSet<String>()
    private val reservations = HashMap<String, Long>()
    private val partialBytes = HashMap<String, Long>()
    private val lastAccess = HashMap<String, Long>()
    private val diskNames = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var reconciled = false
    private var dirtyIndexMutations = 0
    private var firstDirtyAt = 0L
    private var indexFailures = 0

    class Lease internal constructor(val file: File, private val release: () -> Unit) :
        AutoCloseable {
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    fun acquire(key: String): Lease? = synchronized(recordsLock) {
        val file = get(key) ?: return@synchronized null
        val name = diskName(key)
        leases[name] = (leases[name] ?: 0) + 1
        Lease(file) {
            synchronized(recordsLock) {
                val remaining = (leases[name] ?: 1) - 1
                if (remaining == 0) leases.remove(name) else leases[name] = remaining
                flushPendingDeletes()
            }
        }
    }

    /** Leases a file by its physical name; null when it is not a cache file or is gone. */
    fun acquireFile(file: File): Lease? = synchronized(recordsLock) {
        if (file.parentFile?.canonicalFile != root.canonicalFile || !file.isFile) return@synchronized null
        val name = file.name
        leases[name] = (leases[name] ?: 0) + 1
        Lease(file) {
            synchronized(recordsLock) {
                val remaining = (leases[name] ?: 1) - 1
                if (remaining == 0) leases.remove(name) else leases[name] = remaining
                flushPendingDeletes()
            }
        }
    }

    private fun protected(key: String): Boolean =
        (leases[diskName(key)] ?: 0) > 0 || records[key]?.pinned == true ||
                protectedKeys.values.any { keys -> keys.any { diskName(it) == diskName(key) } }

    private fun protectedNames(): Set<String> {
        val names = HashSet<String>(leases.keys)
        protectedKeys.values.forEach { keys -> keys.forEach { names.add(diskName(it)) } }
        records.values.forEach { if (it.pinned) names.add(diskName(it.key)) }
        return names
    }

    /** Space held by transfers in flight for other keys; completed files cannot be evicted for it. */
    private fun inFlightBytesExcept(key: String): Long {
        val name = diskName(key)
        val partial = partialBytes.filterKeys { it != name }.values.sum()
        val reserved = reservations.entries.filter { it.key != name }
            .sumOf { (n, bytes) -> (bytes - (partialBytes[n] ?: 0L)).coerceAtLeast(0L) }
        return partial + reserved
    }

    /** Bytes reserved or already in a partial transfer. No cache walk and no hashing. */
    fun inflightBytes(): Long = synchronized(recordsLock) {
        val partial = partialBytes.values.sum()
        val reserved = reservations.entries.sumOf { (name, bytes) ->
            (bytes - (partialBytes[name] ?: 0L)).coerceAtLeast(0L)
        }
        partial + reserved
    }

    /** Derived from in-memory records and transfer state; no filesystem access. */
    fun accounting(): StorageAccounting = synchronized(recordsLock) {
        ensureIndexLoaded()
        val protectedSet = protectedNames()
        val seen = HashSet<String>()
        var completed = 0L
        var protectedTotal = 0L
        for (record in records.values) {
            val name = diskName(record.key)
            if (!seen.add(name)) continue
            if (name in protectedSet) protectedTotal += record.bytes else completed += record.bytes
        }
        for (name in protectedSet) {
            if (name !in seen) protectedTotal += File(root, name).length()
        }
        StorageAccounting(
            completedBytes = completed,
            protectedBytes = protectedTotal,
            partialBytes = partialBytes.values.sum(),
            reservedBytes = reservations.entries
                .sumOf { (n, bytes) -> (bytes - (partialBytes[n] ?: 0L)).coerceAtLeast(0L) },
        )
    }

    class Reservation internal constructor(private val release: () -> Unit) : AutoCloseable {
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    /**
     * Holds [bytes] for a transfer about to write. Optional reservations are
     * refused when protected, partial and reserved space would leave no room.
     */
    fun reserve(key: String, bytes: Long, optional: Boolean = true): Reservation =
        synchronized(recordsLock) {
            ensureIndexLoaded()
            val name = diskName(key)
            if (bytes < 0 || root.usableSpace - bytes < FREE_SPACE_RESERVE) {
                throw AdmissionException(AdmissionFailure.Storage)
            }
            if (optional && exceedsCapacity(key, bytes)) {
                throw AdmissionException(AdmissionFailure.Capacity)
            }
            reservations[name] = (reservations[name] ?: 0L) + bytes
            Reservation {
                synchronized(recordsLock) {
                    val left = (reservations[name] ?: 0L) - bytes
                    if (left <= 0L) reservations.remove(name) else reservations[name] = left
                }
            }
        }

    /** Records the current size of this key's `.part` staging file; zero clears it. */
    fun notePartial(key: String, bytes: Long) = synchronized(recordsLock) {
        val name = diskName(key)
        if (bytes <= 0L) partialBytes.remove(name) else partialBytes[name] = bytes
    }

    /** Deletes staging files nobody owns and that were idle for [maxIdleMs]. */
    fun expireInactivePartials(maxIdleMs: Long = PARTIAL_IDLE_MS): Long =
        synchronized(recordsLock) {
            val now = nowMs()
            var freed = 0L
            root.listFiles()?.forEach { file ->
                if (!file.isFile || !file.name.endsWith(".part")) return@forEach
                val name = file.name.removeSuffix(".part")
                if (reservations.containsKey(name) || (leases[name] ?: 0) > 0) return@forEach
                if (now - file.lastModified() < maxIdleMs) return@forEach
                val length = file.length()
                if (file.delete()) {
                    freed += length
                    partialBytes.remove(name)
                }
            }
            freed
        }

    /** Rebuilds partial counters and the index from disk; returns tracked bytes minus real bytes. */
    fun reconcile(): Long = synchronized(recordsLock) {
        ensureIndexLoaded()
        reconcileLocked()
        accounting().let { it.completedBytes + it.protectedBytes } - completeBytesOnDisk()
    }

    private fun reconcileLocked() {
        dropMissing()
        backfillUnindexed()
        partialBytes.clear()
        root.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".part") && !file.name.startsWith(STAGING_PUBLISH) &&
                !file.name.startsWith(STAGING_INDEX)
            ) {
                partialBytes[file.name.removeSuffix(".part")] = file.length()
            }
        }
        reconciled = true
    }

    private fun completeBytesOnDisk(): Long =
        root.listFiles()?.filter { it.isFile && it.name != INDEX_NAME && !isStaging(it.name) }
            ?.sumOf { it.length() } ?: 0L

    fun pin(key: String) = synchronized(recordsLock) {
        ensureIndexLoaded()
        val record = records[key] ?: return@synchronized
        if (record.pinned) return@synchronized
        records[key] = record.copy(pinned = true)
        markIndexDirty(force = true)
    }

    fun unpin(key: String) = synchronized(recordsLock) {
        ensureIndexLoaded()
        val record = records[key] ?: return@synchronized
        if (!record.pinned) return@synchronized
        records[key] = record.copy(pinned = false)
        markIndexDirty(force = true)
        flushPendingDeletes()
    }

    /** Writes the batched index now. A failure is retried by the next mutation or flush. */
    fun flushIndex() = synchronized(recordsLock) {
        ensureIndexLoaded()
        if (dirtyIndexMutations > 0) writeIndex()
    }

    private fun markIndexDirty(force: Boolean = false) {
        if (dirtyIndexMutations == 0) firstDirtyAt = nowMs()
        dirtyIndexMutations++
        if (force || dirtyIndexMutations >= INDEX_BATCH || nowMs() - firstDirtyAt >= INDEX_BATCH_MS) {
            writeIndex()
        }
    }

    private fun writeIndex() {
        try {
            persistIndex()
            dirtyIndexMutations = 0
        } catch (_: IOException) {
            // The files stay valid; startup reconciliation recovers unindexed ones.
            indexFailures++
        }
    }

    private fun pendingDeletion(key: String): Boolean =
        pendingDeletes.any { diskName(it) == diskName(key) }

    private fun flushPendingDeletes() {
        val ready = pendingDeletes.filterNot(::protected)
        if (ready.isEmpty()) return
        deleteKeys(ready)
    }

    init {
        root.mkdirs()
        if (maintainOnInit) maintain()
    }

    internal fun maintain() = synchronized(recordsLock) {
        ensureIndexLoaded()
        reconcileLocked()
        expireInactivePartials()
    }

    private fun ensureIndexLoaded() {
        if (indexLoaded) return
        loadIndex()
        indexLoaded = true
    }

    fun fileFor(key: String): File = File(root, diskName(key))

    fun get(key: String): File? = synchronized(recordsLock) {
        if (pendingDeletion(key)) return@synchronized null
        val file = fileFor(key)
        if (!file.isFile) return@synchronized null
        val now = nowMs()
        val name = diskName(key)
        val previous = lastAccess[name]
        lastAccess[name] = now
        if (previous == null || now - previous >= ACCESS_TOUCH_MS) file.setLastModified(now)
        file
    }

    fun setProtectedKeys(owner: String, keys: Collection<String>) = synchronized(recordsLock) {
        if (keys.isEmpty()) protectedKeys.remove(owner)
        else protectedKeys[owner] = keys.filter(String::isNotBlank).toSet()
        flushPendingDeletes()
    }

    fun publishLeased(
        key: String,
        source: File,
        chatId: Long? = null,
        kind: String? = null
    ): Lease =
        synchronized(recordsLock) {
            val owner = "publication:${java.util.UUID.randomUUID()}"
            protectedKeys[owner] = setOf(key)
            try {
                putFileInternal(key, source, chatId, kind, temporary = true)
                checkNotNull(acquire(key)).also {
                    if (exceedsCapacity(key, source.length())) pendingDeletes.add(key)
                }
            } finally {
                protectedKeys.remove(owner)
            }
        }

    fun put(key: String, bytes: ByteArray, chatId: Long? = null, kind: String? = null): File =
        synchronized(recordsLock) {
            ensureIndexLoaded()
            val file = fileFor(key)
            file.parentFile?.mkdirs()
            checkAdmission(key, bytes.size.toLong())
            val staging = File.createTempFile("publish-", ".part", root)
            try {
                FileOutputStream(staging).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                replace(staging, file)
            } finally {
                staging.delete()
            }
            remember(key, chatId, kind ?: inferKind(key), file.length())
            trimIfNeeded()
            if (!file.isFile) throw IOException("cache admission failed")
            file
        }

    fun putFile(key: String, source: File, chatId: Long? = null, kind: String? = null): File =
        putFileInternal(key, source, chatId, kind, temporary = false)

    private fun putFileInternal(
        key: String,
        source: File,
        chatId: Long?,
        kind: String?,
        temporary: Boolean
    ): File =
        synchronized(recordsLock) {
            ensureIndexLoaded()
            val target = fileFor(key)
            target.parentFile?.mkdirs()
            if (!source.exists()) {
                error("media cache source missing")
            }
            val sameFile = source.canonicalFile == target.canonicalFile
            checkAdmission(key, source.length(), temporary, if (sameFile) 0 else source.length())
            if (!sameFile) {
                val staging = File.createTempFile("publish-", ".part", root)
                try {
                    source.inputStream().use { input ->
                        FileOutputStream(staging).use { output ->
                            input.copyTo(output)
                            output.fd.sync()
                        }
                    }
                    replace(staging, target)
                } finally {
                    staging.delete()
                }
            }
            remember(key, chatId, kind ?: inferKind(key), target.length())
            trimIfNeeded()
            if (!target.isFile) throw IOException("cache admission failed")
            target
        }

    private fun exceedsCapacity(key: String, bytes: Long): Boolean {
        if (!enforceSize) return false
        if (bytes > maxBytes) return true
        return protectedBytesExcept(key) + inFlightBytesExcept(key) > maxBytes - bytes
    }

    private fun protectedBytesExcept(key: String): Long {
        val names = protectedNames()
        return names.asSequence().distinct().filter { it != diskName(key) }
            .sumOf { File(root, it).length() }
    }

    private fun checkAdmission(
        key: String,
        bytes: Long,
        temporary: Boolean = false,
        additionalBytes: Long = bytes
    ) {
        if ((leases[diskName(key)] ?: 0) > 0 || pendingDeletion(key)) {
            throw AdmissionException(AdmissionFailure.InUse)
        }
        if (!temporary && exceedsCapacity(key, bytes)) {
            throw AdmissionException(AdmissionFailure.Capacity)
        }
        if (bytes < 0 || root.usableSpace - additionalBytes < FREE_SPACE_RESERVE) {
            throw AdmissionException(AdmissionFailure.Storage)
        }
    }

    private fun replace(source: File, target: File) {
        if (android.os.Build.VERSION.SDK_INT == 0 || android.os.Build.VERSION.SDK_INT >= 26) {
            java.nio.file.Files.move(
                source.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
            return
        }
        if (!source.renameTo(target)) throw IOException("cache atomic replacement failed")
    }

    fun remember(key: String, chatId: Long?, kind: String, bytes: Long) {
        synchronized(recordsLock) {
            ensureIndexLoaded()
            records.keys.filter { it != key && diskName(it) == diskName(key) }
                .forEach(records::remove)
            val pinned = records[key]?.pinned == true
            records[key] = normalized(key, chatId, kind, bytes).copy(pinned = pinned)
            lastAccess[diskName(key)] = nowMs()
            partialBytes.remove(diskName(key))
            markIndexDirty()
        }
    }

    fun attribute(key: String, chatId: Long?, kind: String) {
        synchronized(recordsLock) {
            ensureIndexLoaded()
            val existing = records[key] ?: return
            if (existing.chatId == chatId && existing.kind == kind) return
            records[key] = existing.copy(chatId = chatId, kind = kind)
            markIndexDirty()
        }
    }

    fun records(): List<CacheRecord> = synchronized(recordsLock) {
        ensureIndexLoaded()
        records.values.toList()
    }

    fun usageByKind(): Map<String, Long> = synchronized(recordsLock) {
        ensureIndexLoaded()
        val grouped = records.values.groupBy { it.kind }
            .mapValues { (_, rows) -> rows.sumOf { it.bytes } }
            .toMutableMap()
        val indexed = grouped.values.sum()
        val leftover = (totalBytes() - indexed).coerceAtLeast(0L)
        if (leftover > 0L) {
            grouped[KIND_OTHER] = (grouped[KIND_OTHER] ?: 0L) + leftover
        }
        grouped
    }

    fun usageByChat(): Map<Long, Long> = synchronized(recordsLock) {
        ensureIndexLoaded()
        records.values.mapNotNull { row ->
            val id = row.chatId ?: return@mapNotNull null
            id to row.bytes
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
    }

    fun clearChat(chatId: Long): Long {
        val keys = synchronized(recordsLock) {
            ensureIndexLoaded()
            records.filter { it.value.chatId == chatId }.keys.toList()
        }
        return deleteKeys(keys)
    }

    fun clearKind(kind: String): Long {
        val keys = synchronized(recordsLock) {
            ensureIndexLoaded()
            records.filter { it.value.kind == kind }.keys.toList()
        }
        return deleteKeys(keys)
    }

    fun remove(key: String): Boolean {
        unpin(key)
        deleteKeys(listOf(key), force = true)
        return !fileFor(key).exists()
    }

    /** Deletes all cached files. Used by settings "clear media cache". */
    fun clear(): Long = synchronized(recordsLock) {
        ensureIndexLoaded()
        backfillUnindexed()
        deleteKeys(records.keys.toList())
    }

    fun totalBytes(): Long =
        root.walkTopDown().filter { it.isFile && it.name != INDEX_NAME && !isStaging(it.name) }.sumOf { it.length() }

    /** `null` turns size eviction off. A finite value trims oldest unpinned files down to it. */
    fun setMaxBytes(maxBytes: Long?) = synchronized(recordsLock) {
        if (maxBytes == null) {
            enforceSize = false
        } else {
            enforceSize = true
            this.maxBytes = maxBytes
        }
        trimIfNeeded()
    }

    fun trimIfNeeded() = synchronized(recordsLock) {
        ensureIndexLoaded()
        if (!reconciled) reconcileLocked()
        if (!enforceSize) return@synchronized
        var total = accounting().totalBytes
        if (total <= maxBytes) return@synchronized
        val protectedSet = protectedNames()
        val candidates = records.values
            .filter { diskName(it.key) !in protectedSet }
            .distinctBy { diskName(it.key) }
            .sortedBy { lastAccess[diskName(it.key)] ?: fileFor(it.key).lastModified() }
        for (record in candidates) {
            if (total <= maxBytes) break
            val name = diskName(record.key)
            val file = File(root, name)
            val length = file.length()
            if (!file.exists() || file.delete()) {
                total -= length
                records.keys.filter { diskName(it) == name }.forEach(records::remove)
                lastAccess.remove(name)
            }
        }
        markIndexDirty(force = true)
    }

    private fun deleteKeys(keys: List<String>, force: Boolean = false): Long =
        synchronized(recordsLock) {
        ensureIndexLoaded()
        var freed = 0L
        keys.forEach { key ->
            // Pinned downloads are only removed by an explicit remove().
            if (!force && records[key]?.pinned == true) return@forEach
            if (protected(key)) {
                pendingDeletes.add(key)
                return@forEach
            }
            val file = fileFor(key)
            val len = if (file.exists()) file.length() else 0L
            if (!file.exists() || file.delete()) {
                freed += len
                records.remove(key)
                pendingDeletes.remove(key)
                lastAccess.remove(diskName(key))
            }
        }
            markIndexDirty(force = true)
        freed
    }

    private fun dropMissing() {
        val gone = records.filter { !fileFor(it.key).exists() }.keys
        if (gone.isEmpty()) return
        gone.forEach { records.remove(it) }
        markIndexDirty(force = true)
    }

    private fun backfillUnindexed() {
        val known = records.keys.map { fileFor(it).name }.toSet()
        val files = root.listFiles()?.filter {
            it.isFile && it.name != INDEX_NAME && !isStaging(it.name)
        }.orEmpty()
        var added = false
        files.forEach { file ->
            if (file.name in known) return@forEach
            val key = ORPHAN_PREFIX + file.name
            records[key] = CacheRecord(
                key = key,
                chatId = null,
                kind = KIND_OTHER,
                bytes = file.length(),
            )
            added = true
        }
        if (added) markIndexDirty(force = true)
    }

    private fun loadIndex() {
        if (!indexFile.exists()) return
        var changed = false
        indexFile.readLines().forEach { line ->
            val parts = line.split('\t')
            if (parts.size < 4) return@forEach
            val key = parts[0]
            val chatId = parts[1].toLongOrNull()
            val kind = parts[2].ifBlank { inferKind(key) }
            val bytes = parts[3].toLongOrNull() ?: 0L
            val row = normalized(key, chatId, kind, bytes).copy(pinned = parts.getOrNull(4) == "1")
            if (row.chatId != chatId || row.kind != kind) changed = true
            records[key] = row
        }
        if (changed) markIndexDirty(force = true)
    }

    private fun normalized(key: String, chatId: Long?, kind: String, bytes: Long): CacheRecord {
        val documentId = attributedDocumentId(key)
        val detach = documentId != null && chatId == documentId
        val storedChat = if (detach) null else chatId
        val storedKind = when {
            key.startsWith("gif:") -> KIND_GIFS
            detach && kind != KIND_STICKERS && kind != KIND_PHOTOS -> KIND_GIFS
            else -> kind
        }
        return CacheRecord(key, storedChat, storedKind, bytes)
    }

    private fun persistIndex() {
        val text = records.values.joinToString("\n") { row ->
            val chat = row.chatId?.toString().orEmpty()
            "${row.key}\t$chat\t${row.kind}\t${row.bytes}\t${if (row.pinned) 1 else 0}"
        }
        val staging = File.createTempFile("index-", ".part", root)
        try {
            FileOutputStream(staging).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            replace(staging, indexFile)
        } finally {
            staging.delete()
        }
    }

    private fun isStaging(name: String): Boolean =
        name.endsWith(".part") || name.endsWith(".cancel")

    private fun diskName(key: String): String = diskNames.computeIfAbsent(key) { raw ->
        if (raw.startsWith(ORPHAN_PREFIX)) raw.removePrefix(ORPHAN_PREFIX) else hash(raw)
    }

    private fun hash(key: String): String {
        val digest = checkNotNull(sha256.get())
        digest.reset()
        val bytes = digest.digest(key.toByteArray(Charsets.UTF_8))
        val chars = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xff
            chars[index * 2] = HEX[value ushr 4]
            chars[index * 2 + 1] = HEX[value and 0x0f]
        }
        return String(chars)
    }

    companion object {
        const val FREE_SPACE_RESERVE = 128L * 1024 * 1024
        const val PARTIAL_IDLE_MS = 24L * 60 * 60 * 1000
        private const val INDEX_BATCH = 16
        private const val INDEX_BATCH_MS = 2_000L
        private const val ACCESS_TOUCH_MS = 5_000L
        private val HEX = "0123456789abcdef".toCharArray()
        private val sha256 = object : ThreadLocal<MessageDigest>() {
            override fun initialValue(): MessageDigest = MessageDigest.getInstance("SHA-256")
        }
        private const val STAGING_PUBLISH = "publish-"
        private const val STAGING_INDEX = "index-"

        const val INDEX_NAME = "index.tsv"
        const val ORPHAN_PREFIX = "orphan:"
        const val KIND_PHOTOS = "photos"
        const val KIND_VIDEOS = "videos"
        const val KIND_FILES = "files"
        const val KIND_STICKERS = "stickers"
        const val KIND_GIFS = "gifs"
        const val KIND_OTHER = "other"

        fun inferKind(key: String, mediaKind: String? = null): String {
            val hint = mediaKind.orEmpty()
            return when {
                hint == "photo" || hint == "webpage" || key.startsWith("photo:") -> KIND_PHOTOS
                hint == "gif" || key.startsWith("gif:") -> KIND_GIFS
                hint == "video" || hint == "video_note" || key.startsWith("doc:") && key.endsWith(":thumb") -> KIND_VIDEOS
                hint.startsWith("sticker") || key.startsWith("emoji:") -> KIND_STICKERS
                key.startsWith("avatar:") -> KIND_OTHER
                hint == "document" || hint == "audio" || hint == "voice" -> KIND_FILES
                key.startsWith("doc:") -> KIND_FILES
                else -> KIND_OTHER
            }
        }

        private fun attributedDocumentId(key: String): Long? {
            val body = when {
                key.startsWith("gif:") -> key.removePrefix("gif:")
                key.startsWith("doc:") -> key.removePrefix("doc:")
                else -> return null
            }
            val id = body.substringBefore(':')
            if (id.isEmpty() || id.any { !it.isDigit() }) return null
            return id.toLongOrNull()
        }
    }
}
