package org.monogram.mtproto.crypto

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class MtProtoSecretChatKeyDerivationTest {
    // Deterministic toy DH parameters (not secure; only verify the formula and padding).
    private val p = BigInteger.valueOf(2339584727L).toByteArray()
    private val a = BigInteger.valueOf(1234567L).toByteArray()
    private val b = BigInteger.valueOf(7654321L).toByteArray()
    private val g = BigInteger.TWO

    @Test
    fun `both participants derive the identical 256 byte auth key`() {
        val gA = g.modPow(BigInteger(1, a), BigInteger(1, p)).toByteArray()
        val gB = g.modPow(BigInteger(1, b), BigInteger(1, p)).toByteArray()

        val creatorKeys = MtProtoSecretChatKeyDerivation.derive(gB, a, p)
        val acceptorKeys = MtProtoSecretChatKeyDerivation.derive(gA, b, p)

        assertEquals(256, creatorKeys.authKey.size)
        assertEquals(MtProtoSecretChatKeyDerivation.AUTH_KEY_BYTES, creatorKeys.authKey.size)
        assertTrue(creatorKeys.authKey.contentEquals(acceptorKeys.authKey))
        assertEquals(creatorKeys.keyFingerprint, acceptorKeys.keyFingerprint)
        // Reference fingerprint: last 8 SHA-1 bytes of the auth key as big-endian long.
        val sha1 = java.security.MessageDigest.getInstance("SHA-1").digest(creatorKeys.authKey)
        var expected = 0L
        for (byte in sha1.copyOfRange(sha1.size - 8, sha1.size)) expected = (expected shl 8) or (byte.toLong() and 0xFF)
        assertEquals(expected, creatorKeys.keyFingerprint)
    }

    @Test
    fun `left pads short shared secrets to 256 bytes`() {
        // 3^k mod small prime producing a short big-endian result with leading zero handling.
        val prime = BigInteger(1, byteArrayOf(0, 0, 0, 7)) // 7
        val peer = BigInteger(1, byteArrayOf(3))
        val exp = BigInteger(1, byteArrayOf(2))

        val secret = MtProtoSecretChatKeyDerivation.completeSharedSecret(
            peerPublic = peer.toByteArray(),
            privateExponent = exp.toByteArray(),
            prime = prime.toByteArray(),
        )

        // 3^2 mod 7 = 2 → single significant byte, left-padded to 256.
        assertEquals(256, secret.size)
        assertEquals(256, MtProtoSecretChatKeyDerivation.AUTH_KEY_BYTES)
        assertEquals(2L, BigInteger(1, secret).toLong())
        assertEquals(0, secret[0].toInt())
        assertEquals(2L, secret[255].toLong())
    }

    @Test
    fun `rejects auth keys of the wrong size for fingerprints`() {
        assertThrows(IllegalArgumentException::class.java) {
            MtProtoSecretChatKeyDerivation.fingerprint(ByteArray(128))
        }
    }
}
