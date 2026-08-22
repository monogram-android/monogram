package org.monogram.mtproto.crypto

import java.math.BigInteger
import java.security.MessageDigest

/** Derived secret-chat key material shared by both participants. */
class MtProtoSecretChatKeys(
    val authKey: ByteArray,
    val keyFingerprint: Long,
)

/**
 * Secret-chat DH key completion and identification, matching the reference client
 * (`SecretChatHelper.processAcceptedSecretChat`): `authKey = gA^aOrB mod p`, left-padded to
 * 256 bytes, identified by the last 8 bytes of its SHA-1 digest read as a big-endian long.
 */
object MtProtoSecretChatKeyDerivation {
    const val AUTH_KEY_BYTES = 256

    fun completeSharedSecret(
        peerPublic: ByteArray,
        privateExponent: ByteArray,
        prime: ByteArray,
    ): ByteArray {
        val result = BigInteger(1, peerPublic)
            .modPow(BigInteger(1, privateExponent), BigInteger(1, prime))
            .toByteArray()
        return when {
            result.size > AUTH_KEY_BYTES ->
                result.copyOfRange(result.size - AUTH_KEY_BYTES, result.size)
            result.size < AUTH_KEY_BYTES ->
                ByteArray(AUTH_KEY_BYTES - result.size) + result
            else -> result
        }
    }

    fun fingerprint(authKey: ByteArray): Long {
        require(authKey.size == AUTH_KEY_BYTES) { "Secret chat auth key must be $AUTH_KEY_BYTES bytes" }
        val sha1 = MessageDigest.getInstance("SHA-1").digest(authKey)
        var value = 0L
        for (byte in sha1.copyOfRange(sha1.size - 8, sha1.size)) {
            value = (value shl 8) or (byte.toLong() and 0xFF)
        }
        return value
    }

    fun derive(peerPublic: ByteArray, privateExponent: ByteArray, prime: ByteArray): MtProtoSecretChatKeys {
        val authKey = completeSharedSecret(peerPublic, privateExponent, prime)
        return MtProtoSecretChatKeys(authKey, fingerprint(authKey))
    }
}
