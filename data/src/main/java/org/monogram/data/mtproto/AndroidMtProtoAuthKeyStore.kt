package org.monogram.data.mtproto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AndroidMtProtoAuthKeyStore(context: Context) : MtProtoAuthKeyStore {
    private val directory = File(context.noBackupFilesDir, DIRECTORY_NAME)
    private val mutex = Mutex()

    override suspend fun load(scope: MtProtoAuthKeyScope): MtProtoAuthKeyLoadResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = file(scope)
            if (!file.baseFile.exists()) return@withLock MtProtoAuthKeyLoadResult.Missing
            try {
                val key = existingWrappingKey() ?: return@withLock corrupt(file)
                val blobBytes = readBounded(file)
                try {
                    val blob = EncryptedAuthKeyBlobCodec.decode(blobBytes)
                    val aad = scope.storageKey.toByteArray(Charsets.UTF_8)
                    val plaintext = try {
                        Cipher.getInstance(TRANSFORMATION).run {
                            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob.iv))
                            updateAAD(aad)
                            doFinal(blob.ciphertext)
                        }
                    } finally {
                        aad.fill(0)
                        blob.close()
                    }
                    try {
                        MtProtoAuthKeyLoadResult.Found(MtProtoAuthKeyRecordCodec.decode(plaintext))
                    } finally {
                        plaintext.fill(0)
                    }
                } finally {
                    blobBytes.fill(0)
                }
            } catch (_: Exception) {
                corrupt(file)
            }
        }
    }

    override suspend fun save(scope: MtProtoAuthKeyScope, authKey: StoredMtProtoAuthKey) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = file(scope)
            val parent = checkNotNull(file.baseFile.parentFile)
            check(parent.exists() || parent.mkdirs()) { "Unable to create MTProto auth key directory" }
            val plaintext = MtProtoAuthKeyRecordCodec.encode(authKey)
            val aad = scope.storageKey.toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val ciphertext = try {
                cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
                cipher.updateAAD(aad)
                cipher.doFinal(plaintext)
            } finally {
                plaintext.fill(0)
                aad.fill(0)
            }
            val iv = cipher.iv
            val blob = try {
                EncryptedAuthKeyBlobCodec.encode(iv, ciphertext)
            } finally {
                iv.fill(0)
                ciphertext.fill(0)
            }
            try {
                writeAtomically(file, blob)
            } finally {
                blob.fill(0)
            }
        }
    }

    override suspend fun delete(scope: MtProtoAuthKeyScope) = withContext(Dispatchers.IO) {
        mutex.withLock { file(scope).delete() }
    }

    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) = withContext(Dispatchers.IO) {
        val accountDirectory = accountDirectory(MtProtoAuthKeyScope(accountSlot, environment, 1))
        mutex.withLock {
            accountDirectory.listFiles().orEmpty()
                .filter { it.name.startsWith(DC_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
                .forEach { AtomicFile(it).delete() }
            accountDirectory.delete()
            Unit
        }
    }

    private fun accountDirectory(scope: MtProtoAuthKeyScope) =
        File(File(directory, scope.environment.storageName), scope.accountSlot)

    private fun file(scope: MtProtoAuthKeyScope) =
        AtomicFile(File(accountDirectory(scope), DC_PREFIX + scope.dcId + FILE_SUFFIX))

    private fun corrupt(file: AtomicFile): MtProtoAuthKeyLoadResult.Corrupt {
        file.delete()
        return MtProtoAuthKeyLoadResult.Corrupt
    }

    private fun wrappingKey(): SecretKey {
        existingWrappingKey()?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun existingWrappingKey(): SecretKey? = KeyStore.getInstance(ANDROID_KEY_STORE).run {
        load(null)
        getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun readBounded(file: AtomicFile): ByteArray = file.openRead().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(512)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                require(output.size() + read <= MAX_FILE_BYTES) { "MTProto auth key file exceeds limit" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } finally {
            buffer.fill(0)
        }
    }

    private fun writeAtomically(file: AtomicFile, bytes: ByteArray) {
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }

    private companion object {
        const val DIRECTORY_NAME = "mtproto-auth"
        const val DC_PREFIX = "dc"
        const val FILE_SUFFIX = ".bin"
        const val MAX_FILE_BYTES = 4 * 1024
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "org.monogram.mtproto.auth.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}

internal class EncryptedAuthKeyBlob(
    val iv: ByteArray,
    val ciphertext: ByteArray,
) : AutoCloseable {
    override fun close() {
        iv.fill(0)
        ciphertext.fill(0)
    }
}

internal object EncryptedAuthKeyBlobCodec {
    private const val MAGIC = 0x4d544145
    private const val VERSION = 1
    private const val IV_BYTES = 12
    private const val HEADER_BYTES = 4 + 4 + 4 + 4
    private const val MAX_CIPHERTEXT_BYTES = 1024

    fun encode(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(iv.size == IV_BYTES) { "Invalid MTProto auth key IV length" }
        require(ciphertext.size in 1..MAX_CIPHERTEXT_BYTES) { "Invalid MTProto auth key ciphertext length" }
        return java.nio.ByteBuffer.allocate(HEADER_BYTES + iv.size + ciphertext.size).apply {
            putInt(MAGIC)
            putInt(VERSION)
            putInt(iv.size)
            putInt(ciphertext.size)
            put(iv)
            put(ciphertext)
        }.array()
    }

    fun decode(bytes: ByteArray): EncryptedAuthKeyBlob {
        require(bytes.size >= HEADER_BYTES) { "Truncated MTProto auth key blob" }
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        require(buffer.int == MAGIC) { "Invalid MTProto auth key blob magic" }
        require(buffer.int == VERSION) { "Unsupported MTProto auth key blob version" }
        val ivLength = buffer.int
        val ciphertextLength = buffer.int
        require(ivLength == IV_BYTES) { "Invalid MTProto auth key IV length" }
        require(ciphertextLength in 1..MAX_CIPHERTEXT_BYTES) { "Invalid MTProto auth key ciphertext length" }
        require(buffer.remaining() == ivLength + ciphertextLength) { "Invalid MTProto auth key blob length" }
        val iv = ByteArray(ivLength).also(buffer::get)
        val ciphertext = ByteArray(ciphertextLength).also(buffer::get)
        return EncryptedAuthKeyBlob(iv, ciphertext)
    }
}
