package org.monogram.mtproto.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class RsaPublicKeyTest {
    @Test
    fun parsesTelegramFixtureAndComputesFingerprint() {
        val key = RsaPublicKey.fromPkcs1Pem(PEM)
        assertEquals(-7_596_991_558_377_038_078L, key.fingerprint())
        val encrypted = key.encryptRaw(PEM.toByteArray(Charsets.US_ASCII).copyOf(256))
        assertEquals(256, encrypted.size)
        assertEquals(
            "U2nJEtB2AgpHrm3HB0yhpTQgb0wbesi9Pv/W1v/vULU=",
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(encrypted)),
        )
    }

    @Test
    fun rejectsMalformedAndInvalidInputs() {
        assertThrows(IllegalArgumentException::class.java) { RsaPublicKey.fromPkcs1Pem("bad") }
        val key = RsaPublicKey.fromPkcs1Pem(PEM)
        assertThrows(IllegalArgumentException::class.java) { key.encryptRaw(ByteArray(255)) }
        assertThrows(IllegalArgumentException::class.java) { key.encryptRaw(ByteArray(256) { 0xff.toByte() }) }
    }

    companion object {
        internal val PEM = """
-----BEGIN RSA PUBLIC KEY-----
MIIBCgKCAQEAr4v4wxMDXIaMOh8bayF/NyoYdpcysn5EbjTIOZC0RkgzsRj3SGlu
52QSz+ysO41dQAjpFLgxPVJoOlxXokaOq827IfW0bGCm0doT5hxtedu9UCQKbE8j
lDOk+kWMXHPZFJKWRgKgTu9hcB3y3Vk+JFfLpq3d5ZB48B4bcwrRQnzkx5GhWOFX
x73ZgjO93eoQ2b/lDyXxK4B4IS+hZhjzezPZTI5upTRbs5ljlApsddsHrKk6jJNj
8Ygs/ps8e6ct82jLXbnndC9s8HjEvDvBPH9IPjv5JUlmHMBFZ5vFQIfbpo0u0+1P
n6bkEi5o7/ifoyVv2pAZTRwppTz0EuXD8QIDAQAB
-----END RSA PUBLIC KEY-----
""".trimIndent()
    }
}
