package org.monogram.mtproto.crypto

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.codec.TlBinaryCodec
import org.monogram.mtproto.tl.generated.transport.PQInnerDataDc
import org.monogram.mtproto.tl.generated.transport.ServerDhInnerData_0c7075057b
import org.monogram.mtproto.tl.generated.transport.ServerDhInnerData_0c7075057bCodec
import org.monogram.mtproto.tl.generated.transport.ServerDhParamsOk
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.tl.runtime.TlInt128
import org.monogram.mtproto.tl.runtime.TlInt256

class ServerDhParamsProcessorTest {
    @Test
    fun decryptsAuthenticatedInnerDataWithRandomPadding() {
        val prepared = prepared()
        val inner = inner(prepared)
        val response = encryptedResponse(prepared, inner, paddingBytes = 8)

        assertEquals(inner, ServerDhParamsProcessor.decrypt(prepared, response))
    }

    @Test
    fun rejectsOuterNonceAlignmentAndHashFailures() {
        val prepared = prepared()
        val inner = inner(prepared)
        val valid = encryptedResponse(prepared, inner, paddingBytes = 8)
        assertFailure(ServerDhParamsFailure.NONCE_MISMATCH) {
            ServerDhParamsProcessor.decrypt(prepared, valid.copy(nonce = TlInt128.copyOf(ByteArray(16))))
        }
        assertFailure(ServerDhParamsFailure.INVALID_ENCRYPTED_ANSWER_LENGTH) {
            ServerDhParamsProcessor.decrypt(prepared, valid.copy(encryptedAnswer = TlBytes.copyOf(ByteArray(15))))
        }
        assertFailure(ServerDhParamsFailure.HASH_MISMATCH) {
            ServerDhParamsProcessor.decrypt(prepared, encryptedResponse(prepared, inner, 8, corruptHash = true))
        }
    }

    private fun prepared(): PqAuthPrepared {
        val nonce = TlInt128.copyOf(ByteArray(16) { (it + 1).toByte() })
        val serverNonce = TlInt128.copyOf(ByteArray(16) { (it + 17).toByte() })
        return PqAuthPrepared(
            innerData = PQInnerDataDc(
                pq = TlBytes.copyOf(byteArrayOf(15)),
                p = TlBytes.copyOf(byteArrayOf(3)),
                q = TlBytes.copyOf(byteArrayOf(5)),
                nonce = nonce,
                serverNonce = serverNonce,
                newNonce = TlInt256.copyOf(ByteArray(32) { (it + 33).toByte() }),
                dc = 2,
            ),
            rsaFingerprint = 1L,
        )
    }

    private fun inner(prepared: PqAuthPrepared) = ServerDhInnerData_0c7075057b(
        nonce = prepared.innerData.nonce,
        serverNonce = prepared.innerData.serverNonce,
        g = 3,
        dhPrime = TlBytes.copyOf(ByteArray(256) { 0x7f }),
        gA = TlBytes.copyOf(ByteArray(256) { 0x11 }),
        serverTime = 1_783_001_185,
    )

    private fun encryptedResponse(
        prepared: PqAuthPrepared,
        inner: ServerDhInnerData_0c7075057b,
        paddingBytes: Int,
        corruptHash: Boolean = false,
    ): ServerDhParamsOk {
        val encoded = TlBinaryCodec.encode(ServerDhInnerData_0c7075057bCodec, inner)
        val hash = MessageDigest.getInstance("SHA-1").digest(encoded)
        if (corruptHash) hash[0] = (hash[0].toInt() xor 1).toByte()
        val plaintext = hash + encoded + ByteArray(paddingBytes) { 0x5a }
        require(plaintext.size % 16 == 0)
        val material = MtProtoKeyDerivation.temporaryAesKeyIv(
            prepared.innerData.newNonce.toByteArray(),
            prepared.innerData.serverNonce.toByteArray(),
        )
        val key = material.key
        val iv = material.iv
        material.close()
        val encrypted = AesIge.encrypt(plaintext, key, iv)
        key.fill(0)
        iv.fill(0)
        plaintext.fill(0)
        encoded.fill(0)
        hash.fill(0)
        return ServerDhParamsOk(
            prepared.innerData.nonce,
            prepared.innerData.serverNonce,
            TlBytes.copyOf(encrypted),
        ).also { encrypted.fill(0) }
    }

    private fun assertFailure(expected: ServerDhParamsFailure, block: () -> Unit) {
        assertEquals(expected, assertThrows(ServerDhParamsException::class.java, block).failure)
    }
}
