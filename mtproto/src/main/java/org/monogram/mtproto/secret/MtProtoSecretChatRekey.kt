package org.monogram.mtproto.secret

import java.math.BigInteger
import org.monogram.mtproto.crypto.MtProtoSecretChatKeyDerivation

/**
 * Secret-chat key re-negotiation decisions and future-key computation, matching the reference
 * client (`SecretChatHelper`): re-key after 100 outbound uses or 7 days, only when no exchange
 * is in flight; both sides derive the identical future key from `gA^own mod p` and identify it
 * by the same SHA-1 last-8 fingerprint as the original key.
 */
object MtProtoSecretChatRekey {
    const val KEY_USE_LIMIT = 100
    const val PEER_USE_LIMIT = 120
    const val KEY_MAX_AGE_SECONDS = 60L * 60L * 24L * 7L

    data class FutureKeys(val authKey: ByteArray, val keyFingerprint: Long)

    /**
     * Initiator-side decision: `keyUseCount >= 100 || keyAgeSeconds >= 7 days`, with no exchange
     * in flight (`exchangeId == 0`) and no already-pending future key.
     */
    fun shouldInitiate(
        keyUseCount: Int,
        keyCreateDateSeconds: Long,
        nowSeconds: Long,
        exchangeId: Long,
        hasFutureKey: Boolean,
    ): Boolean {
        require(keyUseCount >= 0) { "key use count must not be negative" }
        if (exchangeId != 0L || hasFutureKey) return false
        val agedOut = keyCreateDateSeconds < nowSeconds - KEY_MAX_AGE_SECONDS
        return keyUseCount >= KEY_USE_LIMIT || agedOut
    }

    /** Responder-side acceptance: the peer may propose once our inbound use count reaches 120. */
    fun shouldAccept(peerRequested: Boolean, keyUseCountIn: Int): Boolean =
        peerRequested && keyUseCountIn >= PEER_USE_LIMIT

    /** Computes the future auth key and fingerprint from the peer's new public part. */
    fun deriveFutureKeys(peerPublic: ByteArray, privateExponent: ByteArray, prime: ByteArray): FutureKeys {
        val derived = MtProtoSecretChatKeyDerivation.derive(peerPublic, privateExponent, prime)
        return FutureKeys(derived.authKey, derived.keyFingerprint)
    }

    /** DH sanity guard reused from the reference: reject degenerate shared secrets. */
    fun isGoodSharedSecret(sharedSecret: ByteArray, prime: ByteArray): Boolean {
        val p = BigInteger(1, prime)
        val value = BigInteger(1, sharedSecret)
        return value.signum() > 0 && value < p.subtract(BigInteger.ONE)
    }
}
