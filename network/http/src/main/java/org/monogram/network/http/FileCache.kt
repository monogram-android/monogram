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
)

class FileCache(
    private val root: File,
    private val maxBytes: Long = 256L * 1024L * 1024L,
    private val maintainOnInit: Boolean = true,
) {
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

    private fun protected(key: String): Boolean =
        (leases[diskName(key)] ?: 0) > 0 || protectedKeys.values.any { keys ->
            keys.any { diskName(it) == diskName(key) }
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
        dropMissing()
        backfillUnindexed()
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
        file.setLastModified(System.currentTimeMillis())
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
                    if (source.length() + protectedBytesExcept(key) > maxBytes) pendingDeletes.add(
                        key
                    )
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

    private fun protectedBytesExcept(key: String): Long {
        val names = leases.keys + protectedKeys.values.flatten().map(::diskName)
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
        if (!temporary && (bytes > maxBytes || protectedBytesExcept(key) > maxBytes - bytes)) {
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
            records[key] = normalized(key, chatId, kind, bytes)
            persistIndex()
        }
    }

    fun attribute(key: String, chatId: Long?, kind: String) {
        synchronized(recordsLock) {
            ensureIndexLoaded()
            val existing = records[key] ?: return
            if (existing.chatId == chatId && existing.kind == kind) return
            records[key] = existing.copy(chatId = chatId, kind = kind)
            persistIndex()
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
        deleteKeys(listOf(key))
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

    fun trimIfNeeded() = synchronized(recordsLock) {
        ensureIndexLoaded()
        val files = root.walkTopDown().filter { it.isFile && it.name != INDEX_NAME && !isStaging(it.name) }.toList()
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return@synchronized
        val ordered = files.sortedBy { it.lastModified() }
        for (file in ordered) {
            if (total <= maxBytes) break
            if (protected(ORPHAN_PREFIX + file.name)) continue
            val len = file.length()
            if (file.delete()) {
                total -= len
                val drop = records.filter { fileFor(it.key).canonicalFile == file.canonicalFile }.keys
                drop.forEach { records.remove(it) }
            }
        }
        persistIndex()
    }

    private fun deleteKeys(keys: List<String>): Long = synchronized(recordsLock) {
        ensureIndexLoaded()
        var freed = 0L
        keys.forEach { key ->
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
            }
        }
        persistIndex()
        freed
    }

    private fun dropMissing() {
        val gone = records.filter { !fileFor(it.key).exists() }.keys
        if (gone.isEmpty()) return
        gone.forEach { records.remove(it) }
        persistIndex()
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
        if (added) persistIndex()
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
            val row = normalized(key, chatId, kind, bytes)
            if (row.chatId != chatId || row.kind != kind) changed = true
            records[key] = row
        }
        if (changed) persistIndex()
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
            "${row.key}\t$chat\t${row.kind}\t${row.bytes}"
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

    private fun diskName(key: String): String =
        if (key.startsWith(ORPHAN_PREFIX)) key.removePrefix(ORPHAN_PREFIX) else hash(key)

    private fun hash(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val FREE_SPACE_RESERVE = 128L * 1024 * 1024
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
