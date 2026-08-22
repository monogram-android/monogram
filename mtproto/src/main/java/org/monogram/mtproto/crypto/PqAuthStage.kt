package org.monogram.mtproto.crypto

import org.monogram.mtproto.tl.generated.transport.PQInnerDataDc
import org.monogram.mtproto.tl.generated.transport.PQInnerDataDcCodec
import org.monogram.mtproto.tl.generated.transport.ReqPqMulti
import org.monogram.mtproto.tl.generated.transport.ReqDhParams
import org.monogram.mtproto.tl.generated.transport.ResPq_0c012ada9f
import org.monogram.mtproto.tl.runtime.TlInt128
import org.monogram.mtproto.tl.runtime.TlInt256
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.codec.TlBinaryCodec

internal enum class PqAuthFailure {
    NONCE_MISMATCH,
    INVALID_DC_ID,
    NO_TRUSTED_RSA_FINGERPRINT,
    AMBIGUOUS_TRUSTED_RSA_FINGERPRINT,
    RSA_KEY_FINGERPRINT_MISMATCH,
}

internal class PqAuthException(val failure: PqAuthFailure) : IllegalArgumentException(
    "PQ auth stage failed: $failure",
)

internal data class PqAuthRequest(
    val request: ReqPqMulti,
    val nonce: TlInt128,
)

internal data class PqAuthPrepared(
    val innerData: PQInnerDataDc,
    val rsaFingerprint: Long,
)

internal object PqAuthStage {
    fun createRequest(entropy: EntropySource = SecureEntropySource): PqAuthRequest {
        val nonceBytes = ByteArray(16)
        entropy.nextBytes(nonceBytes)
        return try {
            val nonce = TlInt128.copyOf(nonceBytes)
            PqAuthRequest(ReqPqMulti(nonce), nonce)
        } finally {
            nonceBytes.fill(0)
        }
    }

    fun prepare(
        request: PqAuthRequest,
        response: ResPq_0c012ada9f,
        trustedRsaFingerprints: Set<Long>,
        dcId: Int,
        entropy: EntropySource = SecureEntropySource,
        limits: PqFactorizationLimits = PqFactorizationLimits(),
    ): PqAuthPrepared {
        if (request.nonce != response.nonce) throw PqAuthException(PqAuthFailure.NONCE_MISMATCH)
        if (dcId == 0) throw PqAuthException(PqAuthFailure.INVALID_DC_ID)
        val matches = response.serverPublicKeyFingerprints.filter { it in trustedRsaFingerprints }.distinct()
        if (matches.isEmpty()) throw PqAuthException(PqAuthFailure.NO_TRUSTED_RSA_FINGERPRINT)
        if (matches.size != 1) throw PqAuthException(PqAuthFailure.AMBIGUOUS_TRUSTED_RSA_FINGERPRINT)
        val factors = PqFactorizer.factor(response.pq.toByteArray(), entropy, limits)
        val newNonceBytes = ByteArray(32)
        entropy.nextBytes(newNonceBytes)
        return try {
            val newNonce = TlInt256.copyOf(newNonceBytes)
            PqAuthPrepared(
                PqInnerDataBuilder.build(
                    factors,
                    response.pq.toByteArray(),
                    request.nonce,
                    response.serverNonce,
                    newNonce,
                    dcId,
                ),
                matches.single(),
            )
        } finally {
            newNonceBytes.fill(0)
        }
    }

    fun buildDhParamsRequest(
        prepared: PqAuthPrepared,
        publicKey: RsaPublicKey,
        entropy: EntropySource = SecureEntropySource,
    ): ReqDhParams {
        if (publicKey.fingerprint() != prepared.rsaFingerprint) {
            throw PqAuthException(PqAuthFailure.RSA_KEY_FINGERPRINT_MISMATCH)
        }
        val innerData = prepared.innerData
        val encoded = TlBinaryCodec.encode(PQInnerDataDcCodec, innerData)
        val encrypted = try {
            RsaHandshakeBlock.encrypt(encoded, publicKey, entropy)
        } finally {
            encoded.fill(0)
        }
        return ReqDhParams(
            nonce = innerData.nonce,
            serverNonce = innerData.serverNonce,
            p = innerData.p,
            q = innerData.q,
            publicKeyFingerprint = prepared.rsaFingerprint,
            encryptedData = TlBytes.copyOf(encrypted),
        ).also { encrypted.fill(0) }
    }
}
