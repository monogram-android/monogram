package org.monogram.mtproto.crypto

import java.security.MessageDigest

internal class AesKeyIv(key: ByteArray, iv: ByteArray) : AutoCloseable {
    private val keyBytes = key.copyOf()
    private val ivBytes = iv.copyOf()
    private var destroyed = false

    val key: ByteArray
        get() {
            check(!destroyed) { "AES key material has been destroyed" }
            return keyBytes.copyOf()
        }

    val iv: ByteArray
        get() {
            check(!destroyed) { "AES key material has been destroyed" }
            return ivBytes.copyOf()
        }

    override fun close() {
        keyBytes.fill(0)
        ivBytes.fill(0)
        destroyed = true
    }
}

internal object MtProtoKeyDerivation {
    fun temporaryAesKeyIv(newNonce: ByteArray, serverNonce: ByteArray): AesKeyIv {
        require(newNonce.size == NEW_NONCE_BYTES) { "newNonce must contain 32 bytes" }
        require(serverNonce.size == SERVER_NONCE_BYTES) { "serverNonce must contain 16 bytes" }

        val temporary = mutableListOf<ByteArray>()
        fun tracked(value: ByteArray): ByteArray = value.also(temporary::add)
        return try {
            val newThenServer = tracked(sha1(tracked(newNonce + serverNonce)))
            val serverThenNew = tracked(sha1(tracked(serverNonce + newNonce)))
            val newThenNew = tracked(sha1(tracked(newNonce + newNonce)))
            val key = tracked(newThenServer + serverThenNew.copyOfRange(0, 12))
            val iv = tracked(
                serverThenNew.copyOfRange(12, 20) + newThenNew + newNonce.copyOfRange(0, 4),
            )
            AesKeyIv(key, iv)
        } finally {
            temporary.forEach { it.fill(0) }
        }
    }

    fun sha1(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(value)

    fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

    /** Derives the MTProto 2.0 message key and AES-256-IGE material. */
    fun messageAesKeyIv(authKey: ByteArray, msgKey: ByteArray, x: Int): AesKeyIv {
        require(authKey.size == AUTH_KEY_BYTES) { "authKey must contain 256 bytes" }
        require(msgKey.size == MESSAGE_KEY_BYTES) { "msgKey must contain 16 bytes" }
        require(x == 0 || x == 8) { "x must be 0 or 8" }

        val authSliceA = authKey.copyOfRange(x, x + 36)
        val authSliceB = authKey.copyOfRange(40 + x, 76 + x)
        val sha256AInput = msgKey + authSliceA
        val sha256BInput = authSliceB + msgKey
        val sha256A = sha256(sha256AInput)
        val sha256B = sha256(sha256BInput)
        return try {
            val key = sha256A.copyOfRange(0, 8) + sha256B.copyOfRange(8, 24) + sha256A.copyOfRange(24, 32)
            val iv = sha256B.copyOfRange(0, 8) + sha256A.copyOfRange(8, 24) + sha256B.copyOfRange(24, 32)
            AesKeyIv(key, iv).also {
                key.fill(0)
                iv.fill(0)
            }
        } finally {
            sha256AInput.fill(0)
            sha256BInput.fill(0)
            authSliceA.fill(0)
            authSliceB.fill(0)
            sha256A.fill(0)
            sha256B.fill(0)
        }
    }

    /** Computes msg_key from the authenticated payload and random padding. */
    fun messageKey(authKey: ByteArray, plaintext: ByteArray, randomPadding: ByteArray, x: Int): ByteArray {
        require(randomPadding.isNotEmpty()) { "randomPadding must not be empty" }
        val payload = plaintext + randomPadding
        return try {
            messageKey(authKey, payload, x)
        } finally {
            payload.fill(0)
        }
    }

    /** Computes msg_key from the complete encrypted payload, including random padding. */
    fun messageKey(authKey: ByteArray, payload: ByteArray, x: Int): ByteArray {
        require(authKey.size == AUTH_KEY_BYTES) { "authKey must contain 256 bytes" }
        require(x == 0 || x == 8) { "x must be 0 or 8" }
        require(payload.isNotEmpty()) { "payload must not be empty" }
        val authSlice = authKey.copyOfRange(88 + x, 120 + x)
        val input = authSlice + payload
        val digest = sha256(input)
        return try {
            digest.copyOfRange(8, 24)
        } finally {
            input.fill(0)
            authSlice.fill(0)
            digest.fill(0)
        }
    }

    fun authKeyAuxHash(authKey: ByteArray): ByteArray = authKeyHashSlice(authKey, 0, 8)

    fun authKeyIdBytes(authKey: ByteArray): ByteArray = authKeyHashSlice(authKey, 12, 20)

    fun newNonceHash(newNonce: ByteArray, authKey: ByteArray, selector: Int): ByteArray {
        require(newNonce.size == NEW_NONCE_BYTES) { "newNonce must contain 32 bytes" }
        require(selector in 1..3) { "selector must be 1, 2, or 3" }
        val auxiliaryHash = authKeyAuxHash(authKey)
        val input = newNonce + byteArrayOf(selector.toByte()) + auxiliaryHash
        val hash = sha1(input)
        return try {
            hash.copyOfRange(4, 20)
        } finally {
            auxiliaryHash.fill(0)
            input.fill(0)
            hash.fill(0)
        }
    }

    fun initialServerSalt(newNonce: ByteArray, serverNonce: ByteArray): ByteArray {
        require(newNonce.size == NEW_NONCE_BYTES) { "newNonce must contain 32 bytes" }
        require(serverNonce.size == SERVER_NONCE_BYTES) { "serverNonce must contain 16 bytes" }
        return ByteArray(8) { index ->
            (newNonce[index].toInt() xor serverNonce[index].toInt()).toByte()
        }
    }

    private fun authKeyHash(authKey: ByteArray): ByteArray {
        require(authKey.size == AUTH_KEY_BYTES) { "authKey must contain 256 bytes" }
        return sha1(authKey)
    }

    private fun authKeyHashSlice(authKey: ByteArray, fromIndex: Int, toIndex: Int): ByteArray {
        val hash = authKeyHash(authKey)
        return try {
            hash.copyOfRange(fromIndex, toIndex)
        } finally {
            hash.fill(0)
        }
    }

    private const val AUTH_KEY_BYTES = 256
    private const val NEW_NONCE_BYTES = 32
    private const val SERVER_NONCE_BYTES = 16
    private const val MESSAGE_KEY_BYTES = 16
}
