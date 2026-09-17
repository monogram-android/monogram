package org.monogram.mtproto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.DataInputStream
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
    fun loadOrCreate(sessionPath: String): ByteArray {
        val path = File(sessionPath).canonicalPath
        val currentAlias = alias(path, ALIAS_PREFIX)
        val legacyAlias = alias(path, LEGACY_ALIAS_PREFIX)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val wrapped = AtomicFile(File("$path.key"))
        // AtomicFile restores an interrupted replacement before returning data.
        val existing = if (wrapped.baseFile.exists() || File("$path.key.bak").exists()) {
            wrapped.readFully()
        } else null
        val wrappingKey = keyStore.getKey(currentAlias, null) as? SecretKey
            ?: keyStore.getKey(legacyAlias, null) as? SecretKey
            ?: run {
                check(existing == null) { "Session wrapping key unavailable" }
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                    init(
                        KeyGenParameterSpec.Builder(
                            currentAlias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build(),
                    )
                }.generateKey()
            }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        if (existing != null) {
            check(existing.size == 1 + 12 + 32 + 16 && existing[0] == 1.toByte())
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, existing.copyOfRange(1, 13)))
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
                !prefix.startsWith(sessionMagic) &&
                    !prefix.startsWith(brandedSessionMagic) &&
                    !prefix.startsWith(legacySessionMagic),
            )
        }
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
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
        prefix + MessageDigest.getInstance("SHA-256")
            .digest(path.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun ByteArray.startsWith(other: ByteArray): Boolean {
        if (size < other.size) return false
        for (index in other.indices) {
            if (this[index] != other[index]) return false
        }
        return true
    }
}
