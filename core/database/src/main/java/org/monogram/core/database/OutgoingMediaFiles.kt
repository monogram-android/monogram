package org.monogram.core.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

data class RetainedMediaSource(val name: String, val size: Long, val sha256: String)

/** The caller supplies an account-owned directory beneath Android's no-backup files directory. */
class OutgoingMediaFiles(directory: File) {
    private val root = directory.canonicalFile
    private val mutex = Mutex()

    suspend fun retain(sources: List<File>, maxTotalBytes: Long): List<RetainedMediaSource> =
        withContext(Dispatchers.IO) {
            require(sources.size in 1..10 && maxTotalBytes > 0)
            mutex.withLock {
                if (!root.isDirectory && !root.mkdirs()) throw IOException("cannot create retained media directory")
                val created = mutableListOf<File>()
                try {
                    var remaining = maxTotalBytes
                    sources.map { source ->
                        currentCoroutineContext().ensureActive()
                        val expectedSize = source.length()
                        if (!source.isFile) throw IOException("source media is missing")
                        if (expectedSize !in 1..remaining) throw IOException("source media is empty or exceeds the batch limit")
                        val name = UUID.randomUUID().toString() + ".bin"
                        val temporary = File(root, "$name.part")
                        val destination = File(root, name)
                        created += temporary
                        created += destination
                        val digest = MessageDigest.getInstance("SHA-256")
                        var copied = 0L
                        source.inputStream().use { input ->
                            FileOutputStream(temporary).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    copied += count
                                    if (copied > remaining || copied > expectedSize) throw IOException(
                                        "source media grew while copying"
                                    )
                                    output.write(buffer, 0, count)
                                    digest.update(buffer, 0, count)
                                }
                                if (copied != expectedSize || source.length() != expectedSize) throw IOException(
                                    "source media changed while copying"
                                )
                                output.fd.sync()
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        if (!temporary.renameTo(destination)) throw IOException("cannot finalize retained media")
                        remaining -= copied
                        RetainedMediaSource(name, copied, digest.digest().hex())
                    }
                } catch (error: Throwable) {
                    created.forEach { it.delete() }
                    throw error
                }
            }
        }

    suspend fun verifiedFile(source: RetainedMediaSource): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = ownedFile(source)
            if (!file.isFile || file.length() != source.size) throw IOException("retained media is missing or changed")
            val digest = MessageDigest.getInstance("SHA-256")
            var verifiedBytes = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    verifiedBytes += count
                    if (verifiedBytes > source.size) throw IOException("retained media is changed")
                    digest.update(buffer, 0, count)
                }
            }
            if (verifiedBytes != source.size || digest.digest()
                    .hex() != source.sha256
            ) throw IOException("retained media is changed")
            file
        }
    }

    // Release only after durable acknowledgement, rejected admission, or an account-owned clear.
    suspend fun release(sources: List<RetainedMediaSource>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val files = sources.map(::ownedFile)
            files.forEach { if (it.exists() && !it.delete()) throw IOException("retained media release failed") }
        }
    }

    internal suspend fun retainedPath(source: RetainedMediaSource): String =
        withContext(Dispatchers.IO) {
            ownedFile(source).absolutePath
        }

    // Caller serializes this Room-reference snapshot with copy/admission under the account mutex.
    internal suspend fun removeUnreferenced(referenced: Set<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!root.exists()) return@withLock
            val files = root.listFiles()
                ?: throw IOException("retained media recovery cannot read directory")
            files.forEach { file ->
                currentCoroutineContext().ensureActive()
                val name = file.name.removeSuffix(".part")
                if (!name.endsWith(".bin")) return@forEach
                val id = name.removeSuffix(".bin")
                if (runCatching {
                        UUID.fromString(id).toString() == id
                    }.getOrDefault(false) && file.name !in referenced && file.isFile && file.canonicalFile == file.absoluteFile) {
                    if (!file.delete()) throw IOException("retained media recovery cleanup failed")
                }
            }
        }
    }

    private fun ownedFile(source: RetainedMediaSource): File {
        if (source.size <= 0 || !source.sha256.matches(Regex("[0-9a-f]{64}"))) {
            throw IOException("invalid retained media descriptor")
        }
        if (!source.name.endsWith(".bin")) throw IOException("invalid retained media name")
        val id = source.name.removeSuffix(".bin")
        val validId = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
        if (!validId) throw IOException("invalid retained media name")
        val file = File(root, source.name)
        if (file.canonicalFile != file.absoluteFile || file.parentFile != root) {
            throw IOException("retained media is outside its owned directory")
        }
        return file
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
