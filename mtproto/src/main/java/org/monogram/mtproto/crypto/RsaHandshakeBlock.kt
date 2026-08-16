package org.monogram.mtproto.crypto

import java.security.MessageDigest

internal class RsaHandshakeBlockException : IllegalStateException(
    "Unable to construct an RSA handshake block within the retry budget",
)

internal object RsaHandshakeBlock {
    private const val PADDED_DATA_BYTES = 192
    private const val AES_KEY_BYTES = 32
    private const val RSA_BLOCK_BYTES = 256

    fun encrypt(
        innerData: ByteArray,
        publicKey: RsaPublicKey,
        entropy: EntropySource = SecureEntropySource,
        maxAttempts: Int = 32,
    ): ByteArray {
        require(innerData.size <= 144) { "RSA handshake inner data exceeds 144 bytes" }
        require(maxAttempts in 1..32) { "maxAttempts must be within 1..32" }
        val paddedData = ByteArray(PADDED_DATA_BYTES)
        innerData.copyInto(paddedData)
        entropy.nextBytes(paddedData, innerData.size)
        try {
            repeat(maxAttempts) {
                val aesKey = ByteArray(AES_KEY_BYTES)
                val dataWithHash = ByteArray(224)
                val rsaInput = ByteArray(RSA_BLOCK_BYTES)
                try {
                    entropy.nextBytes(aesKey)
                    paddedData.copyInto(dataWithHash)
                    val hashInput = aesKey + paddedData
                    val trailingHash = MessageDigest.getInstance("SHA-256").digest(hashInput)
                    hashInput.fill(0)
                    trailingHash.copyInto(dataWithHash, PADDED_DATA_BYTES)
                    dataWithHash.reverse(0, PADDED_DATA_BYTES)
                    val encryptedTail = AesIge.encrypt(dataWithHash, aesKey, ZERO_IV)
                    encryptedTail.copyInto(rsaInput, AES_KEY_BYTES)
                    encryptedTail.fill(0)
                    val tail = rsaInput.copyOfRange(AES_KEY_BYTES, RSA_BLOCK_BYTES)
                    val tailHash = MessageDigest.getInstance("SHA-256").digest(tail)
                    tail.fill(0)
                    for (index in aesKey.indices) rsaInput[index] = (aesKey[index].toInt() xor tailHash[index].toInt()).toByte()
                    tailHash.fill(0)
                    publicKey.encryptRawOrNull(rsaInput)?.let { return it }
                } finally {
                    aesKey.fill(0)
                    dataWithHash.fill(0)
                    rsaInput.fill(0)
                }
            }
        } finally {
            paddedData.fill(0)
        }
        throw RsaHandshakeBlockException()
    }

    private fun EntropySource.nextBytes(destination: ByteArray, offset: Int) {
        if (offset == destination.size) return
        val random = ByteArray(destination.size - offset)
        try {
            nextBytes(random)
            random.copyInto(destination, offset)
        } finally {
            random.fill(0)
        }
    }

    private val ZERO_IV = ByteArray(32)
}
