package org.monogram.network.http

import java.io.File
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
    private val indexFile = File(root, INDEX_NAME)
    private val records = LinkedHashMap<String, CacheRecord>()
    private val recordsLock = Any()
    private var indexLoaded = false

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

    fun get(key: String): File? {
        val file = fileFor(key)
        if (!file.exists()) return null
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    fun put(key: String, bytes: ByteArray, chatId: Long? = null, kind: String? = null): File =
        synchronized(recordsLock) {
            ensureIndexLoaded()
            val file = fileFor(key)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            remember(key, chatId, kind ?: inferKind(key), file.length())
            trimIfNeeded()
            file
        }

    fun putFile(key: String, source: File, chatId: Long? = null, kind: String? = null): File =
        synchronized(recordsLock) {
            ensureIndexLoaded()
            val target = fileFor(key)
            target.parentFile?.mkdirs()
            if (!source.exists()) {
                error("media cache source missing")
            }
            if (source.canonicalFile != target.canonicalFile) {
                source.copyTo(target, overwrite = true)
            }
            remember(key, chatId, kind ?: inferKind(key), target.length())
            trimIfNeeded()
            target
        }

    fun remember(key: String, chatId: Long?, kind: String, bytes: Long) {
        synchronized(recordsLock) {
            ensureIndexLoaded()
            records[key] = CacheRecord(key = key, chatId = chatId, kind = kind, bytes = bytes)
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
        var freed = 0L
        root.walkTopDown().filter { it.isFile && it.name != INDEX_NAME }.forEach { file ->
            val len = file.length()
            if (file.delete()) freed += len
        }
        records.clear()
        persistIndex()
        freed
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
            val file = fileFor(key)
            val len = if (file.exists()) file.length() else records[key]?.bytes ?: 0L
            if (!file.exists() || file.delete()) {
                freed += len
                records.remove(key)
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
        indexFile.readLines().forEach { line ->
            val parts = line.split('\t')
            if (parts.size < 4) return@forEach
            val key = parts[0]
            val chatId = parts[1].toLongOrNull()
            val kind = parts[2].ifBlank { inferKind(key) }
            val bytes = parts[3].toLongOrNull() ?: 0L
            records[key] = CacheRecord(key, chatId, kind, bytes)
        }
    }

    private fun persistIndex() {
        val text = records.values.joinToString("\n") { row ->
            val chat = row.chatId?.toString().orEmpty()
            "${row.key}\t$chat\t${row.kind}\t${row.bytes}"
        }
        indexFile.writeText(text)
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
        const val INDEX_NAME = "index.tsv"
        const val ORPHAN_PREFIX = "orphan:"
        const val KIND_PHOTOS = "photos"
        const val KIND_VIDEOS = "videos"
        const val KIND_FILES = "files"
        const val KIND_STICKERS = "stickers"
        const val KIND_OTHER = "other"

        fun inferKind(key: String, mediaKind: String? = null): String {
            val hint = mediaKind.orEmpty()
            return when {
                hint == "photo" || hint == "webpage" || key.startsWith("photo:") -> KIND_PHOTOS
                hint == "video" || hint == "video_note" || hint == "gif" || key.startsWith("doc:") && key.endsWith(":thumb") -> KIND_VIDEOS
                hint.startsWith("sticker") || key.startsWith("emoji:") -> KIND_STICKERS
                key.startsWith("avatar:") -> KIND_OTHER
                hint == "document" || hint == "audio" || hint == "voice" -> KIND_FILES
                key.startsWith("doc:") -> KIND_FILES
                else -> KIND_OTHER
            }
        }
    }
}
