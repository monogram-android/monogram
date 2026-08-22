package org.monogram.mtproto.crypto

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MtProtoSecretMessageCipherTest {
    private val authKey = ByteArray(256) { (it * 7).toByte() }
    private val fingerprint = MtProtoSecretChatKeyDerivation.fingerprint(authKey)
    private val plaintext = "payload".toByteArray() + ByteArray(9) { 42 } // 16 bytes, already aligned

    @Test
    fun `derives reference key data with incoming and outgoing directions`() {
        val outgoing = MtProtoSecretMessageCipher.generateMessageKeys(authKey, msgKey(), incoming = false)
        val incoming = MtProtoSecretMessageCipher.generateMessageKeys(authKey, msgKey(), incoming = true)

        // Independent derivation straight from the reference formulas.
        fun sha256(vararg parts: ByteArray) = MessageDigest.getInstance("SHA-256").digest(parts.reduce(ByteArray::plus))
        val xOut = 0
        val aOut = sha256(msgKey(), authKey.copyOfRange(xOut, xOut + 36))
        val bOut = sha256(authKey.copyOfRange(40 + xOut, 40 + xOut + 36) + msgKey())
        val expectedOut = aOut.copyOfRange(0, 8) + bOut.copyOfRange(8, 24) + aOut.copyOfRange(24, 32)

        assertArrayEquals(expectedOut, outgoing.aesKey)
        // Directions must use different key material.
        assertNotEquals(outgoing.aesKey.contentHashCode(), incoming.aesKey.contentHashCode())
    }

    @Test
    fun `round trips packets with random padding and correct header layout`() {
        val packet = MtProtoSecretMessageCipher.encrypt(
            authKey, incoming = false, keyFingerprint = fingerprint, plaintext = plaintext,
        )

        assertEquals(fingerprint.toLong(), readLong(packet))
        assertTrue(packet.size % 16 == 8) // header (24) plus 16-aligned ciphertext

        val decrypted = MtProtoSecretMessageCipher.decryptPacket(authKey, incoming = false, packet = packet)

        assertArrayEquals(msgKeyFullFor(plaintext), decrypted.messageKey)
        assertArrayEquals(plaintext, decrypted.paddedPlaintext.copyOf(plaintext.size))
        // Plaintext was already 16-aligned, so the packet carries zero random padding.
        assertEquals(plaintext.size + 24 + (16 - plaintext.size % 16) % 16, packet.size - 0 - ((16 - plaintext.size % 16) % 16))
    }

    @Test
    fun `rejects packets sealed under a different key`() {
        val otherAuthKey = ByteArray(256) { (it + 3).toByte() }
        val packet = MtProtoSecretMessageCipher.encrypt(
            authKey, incoming = false, keyFingerprint = fingerprint, plaintext = plaintext,
        )

        assertThrows(IllegalStateException::class.java) {
            MtProtoSecretMessageCipher.decryptPacket(otherAuthKey, incoming = false, packet = packet)
        }
    }

    private fun msgKey(): ByteArray = ByteArray(16) { (it * 11).toByte() }

    private fun msgKeyFullFor(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(authKey, 88, 32)
        return digest.digest(data).copyOfRange(8, 24)
    }

    @Suppress("SameParameterValue")
    private fun readLong(source: ByteArray): Long {
        var value = 0L
        for (index in 0 until 8) value = (value shl 8) or (source[index].toLong() and 0xFF)
        return value
    }
}
