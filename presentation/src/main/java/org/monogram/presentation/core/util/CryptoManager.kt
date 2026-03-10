package org.monogram.presentation.core.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CryptoManager {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }

    private val algorithm = KeyProperties.KEY_ALGORITHM_AES
    private val blockMode = KeyProperties.BLOCK_MODE_GCM
    private val padding = KeyProperties.ENCRYPTION_PADDING_NONE
    private val transformation = "$algorithm/$blockMode/$padding"
    private val keyAlias = "webapp_secure_key"

    private fun getKey(): SecretKey {
        val existingKey = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        return existingKey?.secretKey ?: createKey()
    }

    private fun createKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(algorithm, "AndroidKeyStore")
        val keySpec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(blockMode)
            .setEncryptionPaddings(padding)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }

    fun encrypt(data: String): String? {
        if (data.isEmpty()) return null
        try {
            val cipher = Cipher.getInstance(transformation)
            cipher.init(Cipher.ENCRYPT_MODE, getKey())

            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(data.toByteArray(Charsets.UTF_8))

            val ivString = Base64.encodeToString(iv, Base64.DEFAULT).trim()
            val encryptedString = Base64.encodeToString(encryptedBytes, Base64.DEFAULT).trim()

            return "$ivString:$encryptedString"
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun decrypt(encryptedData: String?): String? {
        if (encryptedData.isNullOrEmpty()) return null
        try {
            val parts = encryptedData.split(":")
            if (parts.size != 2) return null

            val iv = Base64.decode(parts[0], Base64.DEFAULT)
            val encryptedBytes = Base64.decode(parts[1], Base64.DEFAULT)

            val cipher = Cipher.getInstance(transformation)
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, getKey(), spec)

            val decodedBytes = cipher.doFinal(encryptedBytes)
            return String(decodedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}