package org.monogram.mtproto.crypto

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal object AesIge {
    fun encrypt(plaintext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        transform(plaintext, key, iv, Cipher.ENCRYPT_MODE)

    fun decrypt(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        transform(ciphertext, key, iv, Cipher.DECRYPT_MODE)

    private fun transform(input: ByteArray, key: ByteArray, iv: ByteArray, mode: Int): ByteArray {
        require(key.size == KEY_BYTES) { "AES-IGE requires a 32-byte key" }
        require(iv.size == IV_BYTES) { "AES-IGE requires a 32-byte IV" }
        require(input.isNotEmpty()) { "AES-IGE input must not be empty" }
        require(input.size % BLOCK_BYTES == 0) { "AES-IGE input must be block-aligned" }

        val keyCopy = key.copyOf()
        val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(mode, SecretKeySpec(keyCopy, "AES"))
        }
        val output = ByteArray(input.size)
        var previousCiphertext = iv.copyOfRange(0, BLOCK_BYTES)
        var previousPlaintext = iv.copyOfRange(BLOCK_BYTES, IV_BYTES)
        val mixed = ByteArray(BLOCK_BYTES)
        val transformed = ByteArray(BLOCK_BYTES)

        return try {
            for (offset in input.indices step BLOCK_BYTES) {
                if (mode == Cipher.ENCRYPT_MODE) {
                    xorBlock(input, offset, previousCiphertext, mixed)
                    check(cipher.update(mixed, 0, BLOCK_BYTES, transformed, 0) == BLOCK_BYTES)
                    xorBlock(transformed, 0, previousPlaintext, output, offset)
                    input.copyInto(previousPlaintext, startIndex = offset, endIndex = offset + BLOCK_BYTES)
                    output.copyInto(previousCiphertext, startIndex = offset, endIndex = offset + BLOCK_BYTES)
                } else {
                    xorBlock(input, offset, previousPlaintext, mixed)
                    check(cipher.update(mixed, 0, BLOCK_BYTES, transformed, 0) == BLOCK_BYTES)
                    xorBlock(transformed, 0, previousCiphertext, output, offset)
                    input.copyInto(previousCiphertext, startIndex = offset, endIndex = offset + BLOCK_BYTES)
                    output.copyInto(previousPlaintext, startIndex = offset, endIndex = offset + BLOCK_BYTES)
                }
            }
            check(cipher.doFinal().isEmpty())
            output
        } finally {
            keyCopy.fill(0)
            previousCiphertext.fill(0)
            previousPlaintext.fill(0)
            mixed.fill(0)
            transformed.fill(0)
        }
    }

    private fun xorBlock(
        source: ByteArray,
        sourceOffset: Int,
        mask: ByteArray,
        destination: ByteArray,
        destinationOffset: Int = 0,
    ) {
        repeat(BLOCK_BYTES) { index ->
            destination[destinationOffset + index] =
                (source[sourceOffset + index].toInt() xor mask[index].toInt()).toByte()
        }
    }

    private const val BLOCK_BYTES = 16
    private const val KEY_BYTES = 32
    private const val IV_BYTES = 32
}
