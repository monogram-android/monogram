package org.monogram.mtproto.crypto

import java.security.MessageDigest
import java.security.SecureRandom

class MtProtoSecretMessageKeys(val aesKey: ByteArray, val aesIv: ByteArray)

/**
 * Secret-chat message encryption (`SecretChatHelper` lines 655-683 / `MessageKeyData` version 2).
 *
 * Packet layout: `key_fingerprint(8) || message_key(16) || AES-IGE(padded plaintext)`.
 * - message_key_full = SHA256(auth_key[88+x .. 88+x+32] || plaintext)[8 .. 24], where x = 8 for incoming.
 * - sha256_a = SHA256(message_key(16) || auth_key[x .. x+36]); sha256_b = SHA256(auth_key[40+x .. 40+x+36] || message_key(16)).
 * - aes_key = a[0..8] b[8..24] a[24..32]; aes_iv = b[0..8] a[8..24] b[24..32].
 * Plaintext is padded with random bytes to a 16-byte boundary before encryption; the caller's
 * TL layer reads only its own fields from the decrypted buffer.
 */
object MtProtoSecretMessageCipher {
    fun generateMessageKeys(authKey: ByteArray, messageKey16: ByteArray, incoming: Boolean): MtProtoSecretMessageKeys {
        require(authKey.size == MtProtoSecretChatKeyDerivation.AUTH_KEY_BYTES) {
            "Secret chat auth key must be ${MtProtoSecretChatKeyDerivation.AUTH_KEY_BYTES} bytes"
        }
        require(messageKey16.size == MESSAGE_KEY_BYTES) { "message key must be $MESSAGE_KEY_BYTES bytes" }
        val x = if (incoming) 8 else 0

        val sha256A = MessageDigest.getInstance("SHA-256")
            .digest(messageKey16 + authKey.copyOfRange(x, x + 36))
        val sha256B = MessageDigest.getInstance("SHA-256")
            .digest(authKey.copyOfRange(40 + x, 40 + x + 36) + messageKey16)

        val aesKey = ByteArray(32)
        System.arraycopy(sha256A, 0, aesKey, 0, 8)
        System.arraycopy(sha256B, 8, aesKey, 8, 16)
        System.arraycopy(sha256A, 24, aesKey, 24, 8)

        val aesIv = ByteArray(32)
        System.arraycopy(sha256B, 0, aesIv, 0, 8)
        System.arraycopy(sha256A, 8, aesIv, 8, 16)
        System.arraycopy(sha256B, 24, aesIv, 24, 8)

        return MtProtoSecretMessageKeys(aesKey, aesIv)
    }

    fun messageKeyFull(authKey: ByteArray, incoming: Boolean, plaintext: ByteArray): ByteArray {
        val x = if (incoming) 8 else 0
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(authKey, 88 + x, 32)
        return digest.digest(plaintext).copyOfRange(8, 24)
    }

    fun encrypt(
        authKey: ByteArray,
        incoming: Boolean,
        keyFingerprint: Long,
        plaintext: ByteArray,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        require(plaintext.isNotEmpty()) { "Secret chat payloads must not be empty" }
        val paddingLength = (16 - (plaintext.size % 16)) % 16
        val padded = plaintext + ByteArray(paddingLength).also(random::nextBytes)
        val messageKey = messageKeyFull(authKey, incoming, padded)
        val keys = generateMessageKeys(authKey, messageKey, incoming)
        val ciphertext = AesIge.encrypt(padded, keys.aesKey, keys.aesIv)
        return packet(keyFingerprint, messageKey, ciphertext)
    }

    /** Decrypts a packet and returns the padded plaintext; callers strip protocol padding. */
    fun decryptPacket(
        authKey: ByteArray,
        incoming: Boolean,
        packet: ByteArray,
    ): DecryptedSecretPacket {
        require(packet.size >= PACKET_HEADER_BYTES + 16) { "Secret chat packet is too short" }
        var cursor = 0L
        val fingerprint = readLong(packet, cursor).also { cursor += 8 }
        val messageKey = packet.copyOfRange(cursor.toInt(), cursor.toInt() + MESSAGE_KEY_BYTES).also { cursor += MESSAGE_KEY_BYTES }
        val ciphertext = packet.copyOfRange(cursor.toInt(), packet.size)
        if (fingerprint != MtProtoSecretChatKeyDerivation.fingerprint(authKey)) {
            throw IllegalStateException("Secret chat key fingerprint mismatch")
        }
        val keys = generateMessageKeys(authKey, messageKey, incoming)
        val padded = AesIge.decrypt(ciphertext, keys.aesKey, keys.aesIv)
        return DecryptedSecretPacket(fingerprint, messageKey, padded)
    }

    data class DecryptedSecretPacket(
        val keyFingerprint: Long,
        val messageKey: ByteArray,
        val paddedPlaintext: ByteArray,
    )

    private fun packet(fingerprint: Long, messageKey: ByteArray, ciphertext: ByteArray): ByteArray {
        val out = ByteArray(PACKET_HEADER_BYTES + ciphertext.size)
        writeLong(out, 0, fingerprint)
        System.arraycopy(messageKey, 0, out, 8, MESSAGE_KEY_BYTES)
        System.arraycopy(ciphertext, 0, out, PACKET_HEADER_BYTES, ciphertext.size)
        return out
    }

    private fun readLong(source: ByteArray, offset: Long): Long {
        require(offset >= 0 && offset + 8 <= source.size) { "packet too short for fingerprint" }
        var value = 0L
        var index = offset
        repeat(8) {
            value = (value shl 8) or (source[index.toInt()].toLong() and 0xFF)
            index += 1
        }
        return value
    }

    private fun writeLong(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 8) target[offset + index] = (value ushr ((7 - index) * 8)).toByte()
    }

    private const val MESSAGE_KEY_BYTES = 16
    private const val PACKET_HEADER_BYTES = 24
}
