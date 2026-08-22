package org.monogram.mtproto.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.tl.generated.transport.ResPq_0c012ada9f
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.tl.runtime.TlInt128

class PqAuthStageTest {
    @Test
    fun preparesCanonicalInnerDataAndSelectsTrustedFingerprint() {
        val entropy = SequenceEntropy(0x11, 0x22)
        val request = PqAuthStage.createRequest(entropy)
        val response = ResPq_0c012ada9f(
            nonce = request.nonce,
            serverNonce = TlInt128.copyOf(hex("63248F6748214EAB8A2F4CC876E11974")),
            pq = TlBytes.copyOf(hex("2E9CDB98C80CDA4B")),
            serverPublicKeyFingerprints = listOf(7L, 42L),
        )

        val prepared = PqAuthStage.prepare(request, response, setOf(42L), 2, entropy)

        assertEquals(42L, prepared.rsaFingerprint)
        assertArrayEquals(hex("6A794259"), prepared.innerData.p.toByteArray())
        assertArrayEquals(hex("7012C543"), prepared.innerData.q.toByteArray())
        assertArrayEquals(hex("2E9CDB98C80CDA4B"), prepared.innerData.pq.toByteArray())
        assertEquals(2, prepared.innerData.dc)
    }

    @Test
    fun rejectsNonceAndFingerprintFailures() {
        val entropy = SequenceEntropy(1, 2)
        val request = PqAuthStage.createRequest(entropy)
        val response = ResPq_0c012ada9f(
            nonce = TlInt128.copyOf(ByteArray(16)),
            serverNonce = TlInt128.copyOf(ByteArray(16)),
            pq = TlBytes.copyOf(hex("2E9CDB98C80CDA4B")),
            serverPublicKeyFingerprints = listOf(42L),
        )
        assertEquals(PqAuthFailure.NONCE_MISMATCH, assertThrows(PqAuthException::class.java) {
            PqAuthStage.prepare(request, response, setOf(42L), 2, entropy)
        }.failure)

        val matching = response.copy(nonce = request.nonce, serverPublicKeyFingerprints = listOf(42L, 43L))
        assertEquals(PqAuthFailure.AMBIGUOUS_TRUSTED_RSA_FINGERPRINT, assertThrows(PqAuthException::class.java) {
            PqAuthStage.prepare(request, matching, setOf(42L, 43L), 2, entropy)
        }.failure)
    }

    @Test
    fun buildsReqDhParamsWithSelectedRsaKey() {
        val entropy = XorShiftEntropy()
        val key = RsaPublicKey.fromPkcs1Pem(RsaPublicKeyTest.PEM)
        val request = PqAuthStage.createRequest(entropy)
        val response = ResPq_0c012ada9f(
            nonce = request.nonce,
            serverNonce = TlInt128.copyOf(hex("63248F6748214EAB8A2F4CC876E11974")),
            pq = TlBytes.copyOf(hex("2E9CDB98C80CDA4B")),
            serverPublicKeyFingerprints = listOf(key.fingerprint()),
        )
        val prepared = PqAuthStage.prepare(request, response, setOf(key.fingerprint()), 2, entropy)

        val dhRequest = PqAuthStage.buildDhParamsRequest(prepared, key, entropy)

        assertEquals(key.fingerprint(), dhRequest.publicKeyFingerprint)
        assertEquals(256, dhRequest.encryptedData.toByteArray().size)
        assertEquals(prepared.innerData.p, dhRequest.p)
        assertEquals(prepared.innerData.q, dhRequest.q)
        assertEquals(request.nonce, dhRequest.nonce)
        assertEquals(response.serverNonce, dhRequest.serverNonce)

        val mismatched = prepared.copy(rsaFingerprint = prepared.rsaFingerprint xor 1L)
        assertEquals(PqAuthFailure.RSA_KEY_FINGERPRINT_MISMATCH, assertThrows(PqAuthException::class.java) {
            PqAuthStage.buildDhParamsRequest(mismatched, key, entropy)
        }.failure)
    }

    private class SequenceEntropy(private vararg val values: Int) : EntropySource {
        private var index = 0
        override fun nextBytes(destination: ByteArray) {
            destination.fill(values[index++ % values.size].toByte())
        }
    }

    private class XorShiftEntropy : EntropySource {
        private var state = 0x6a09e667f3bcc909uL
        override fun nextBytes(destination: ByteArray) {
            destination.indices.forEach { index ->
                state = state xor (state shl 13)
                state = state xor (state shr 7)
                state = state xor (state shl 17)
                destination[index] = state.toByte()
            }
        }
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
