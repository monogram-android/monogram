package org.monogram.mtproto.crypto

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-CTR transform used for Telegram CDN file chunks. */
internal object AesCtr {
    fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        transform(data, key, iv)

    private fun transform(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "AES-CTR requires a 32-byte key" }
        require(iv.size == IV_BYTES) { "AES-CTR requires a 16-byte IV" }
        val keyCopy = key.copyOf()
        val ivCopy = iv.copyOf()
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyCopy, "AES"), IvParameterSpec(ivCopy))
        return cipher.doFinal(if (data.isEmpty()) ByteArray(0) else data.copyOf())
    }

    private const val KEY_BYTES = 32
    private const val IV_BYTES = 16
}
