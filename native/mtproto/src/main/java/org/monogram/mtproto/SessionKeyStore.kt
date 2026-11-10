package org.monogram.mtproto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.util.AtomicFile
import com.sun.jna.NativeLibrary
import java.io.DataInputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only the random data key crosses FFI; Telegram authorization stays native. */
internal object SessionKeyStore {
    private const val ALIAS_PREFIX = "monogram.session."
    private const val LEGACY_ALIAS_PREFIX = "monore.session."
    private val sessionMagic = byteArrayOf(0xC3.toByte(), 0xA7.toByte(), 0x5E, 0x01)
    private val brandedSessionMagic = "MONOGRAM_SESSION\u0001".toByteArray(Charsets.US_ASCII)
    private val legacySessionMagic = "MONORE_SESSION\u0001".toByteArray(Charsets.US_ASCII)

    @Synchronized
    fun loadOrCreate(sessionPath: String): ByteArray = loadOrCreate(sessionPath, null)

    @Synchronized
    fun promote(source: File, destination: File) {
        check(source.isFile) { "Session source unavailable" }
        val target = destination.canonicalFile
        check(
            !target.exists() && !File("${target.path}.key").exists() && !File("${target.path}.key.bak").exists()
        ) { "Session destination already exists" }
        check(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val key = loadOrCreate(source.path)
        try {
            loadOrCreate(target.path, key).fill(0)
            check(source.renameTo(target)) { "Unable to promote account session" }
        } catch (failure: Throwable) {
            File("${target.path}.key").delete()
            throw failure
        } finally {
            key.fill(0)
        }
    }

    /** Caller must hold exclusive ownership proven by a durable promotion journal. */
    @Synchronized
    fun recoverPromotion(source: File, destination: File) {
        check(!isSymlink(source) && !isSymlink(destination)) { "Symbolic session path" }
        val from = source.canonicalFile
        val to = destination.canonicalFile
        val sourceKeyFile = File("${from.path}.key")
        val targetKeyFile = File("${to.path}.key")
        check(setOf(from.path, to.path, sourceKeyFile.path, targetKeyFile.path).size == 4) {
            "Overlapping session paths"
        }
        for (key in listOf(sourceKeyFile, targetKeyFile)) {
            check(stat(File("${key.path}.bak")) == null && stat(File("${key.path}.new")) == null) {
                "Ambiguous wrapped key state"
            }
            val keyStat = stat(key)
            check(keyStat != null && OsConstants.S_ISREG(keyStat.st_mode)) { "Wrapped session key unavailable" }
        }
        val sourceStat = stat(from)
        val targetStat = stat(to)
        val beforeRename =
            sourceStat != null && OsConstants.S_ISREG(sourceStat.st_mode) && targetStat == null
        val afterRename =
            sourceStat == null && targetStat != null && OsConstants.S_ISREG(targetStat.st_mode)
        check(beforeRename || afterRename) { "Ambiguous session state" }

        val sourceKey = readExistingWrappedKey(from.path, sourceKeyFile)
        try {
            val targetKey = readExistingWrappedKey(to.path, targetKeyFile)
            try {
                check(MessageDigest.isEqual(sourceKey, targetKey)) { "Session keys do not match" }
            } finally {
                targetKey.fill(0)
            }
        } finally {
            sourceKey.fill(0)
        }
        if (beforeRename) {
            // renameat2 refuses to replace any destination, including one created concurrently.
            val rename = NativeLibrary.getInstance("c").getFunction("renameat2")
            check(rename.invokeInt(arrayOf<Any>(-100, from.path, -100, to.path, 1)) == 0) {
                "Unable to recover account session without replacement"
            }
        }
    }

    private fun stat(file: File): StructStat? = try {
        Os.lstat(file.path)
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) null else throw error
    }

    private fun isSymlink(file: File): Boolean =
        stat(file)?.let { OsConstants.S_ISLNK(it.st_mode) } ?: false

    private fun readExistingWrappedKey(path: String, file: File): ByteArray {
        check(file.length() == 61L) { "Invalid wrapped session key" }
        val bytes = file.readBytes()
        check(bytes.size == 1 + 12 + 32 + 16 && bytes[0] == 1.toByte()) { "Invalid wrapped session key" }
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val wrappingKey = (keyStore.getKey(alias(path, ALIAS_PREFIX), null) ?: keyStore.getKey(
            alias(
                path, LEGACY_ALIAS_PREFIX
            ), null
        )) as? SecretKey ?: error("Session wrapping key unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, bytes.copyOfRange(1, 13))
        )
        cipher.updateAAD(path.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(bytes, 13, bytes.size - 13).also { check(it.size == 32) }
    }

    private fun loadOrCreate(sessionPath: String, importedKey: ByteArray?): ByteArray {
        val path = File(sessionPath).canonicalPath
        val currentAlias = alias(path, ALIAS_PREFIX)
        val legacyAlias = alias(path, LEGACY_ALIAS_PREFIX)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val wrapped = AtomicFile(File("$path.key"))
        // AtomicFile restores an interrupted replacement before returning data.
        val existing = if (wrapped.baseFile.exists() || File("$path.key.bak").exists()) {
            wrapped.readFully()
        } else null
        val wrappingKey = keyStore.getKey(currentAlias, null) as? SecretKey ?: keyStore.getKey(
            legacyAlias, null
        ) as? SecretKey ?: run {
            check(existing == null) { "Session wrapping key unavailable" }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(
                        currentAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256).build(),
                )
            }.generateKey()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        if (existing != null) {
            check(existing.size == 1 + 12 + 32 + 16 && existing[0] == 1.toByte())
            cipher.init(
                Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, existing.copyOfRange(1, 13))
            )
            cipher.updateAAD(path.toByteArray(Charsets.UTF_8))
            return cipher.doFinal(existing, 13, existing.size - 13).also { check(it.size == 32) }
        }
        // Never replace a missing key for an existing encrypted session.
        val session = File(path)
        if (session.isFile && session.length() > 0L) {
            val n = minOf(
                session.length().toInt(),
                maxOf(sessionMagic.size, brandedSessionMagic.size, legacySessionMagic.size),
            )
            val prefix = DataInputStream(session.inputStream()).use { stream ->
                ByteArray(n).also(stream::readFully)
            }
            check(
                !prefix.startsWith(sessionMagic) && !prefix.startsWith(brandedSessionMagic) && !prefix.startsWith(
                    legacySessionMagic
                ),
            )
        }
        val key = importedKey?.copyOf() ?: ByteArray(32).also(SecureRandom()::nextBytes)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
            cipher.updateAAD(path.toByteArray(Charsets.UTF_8))
            val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(key)
            val stream = wrapped.startWrite()
            try {
                stream.write(bytes)
                wrapped.finishWrite(stream)
            } catch (failure: Throwable) {
                wrapped.failWrite(stream)
                throw failure
            }
            return key
        } catch (failure: Throwable) {
            key.fill(0)
            throw failure
        }
    }

    private fun alias(path: String, prefix: String): String =
        prefix + MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun ByteArray.startsWith(other: ByteArray): Boolean {
        if (size < other.size) return false
        for (index in other.indices) {
            if (this[index] != other[index]) return false
        }
        return true
    }
}
