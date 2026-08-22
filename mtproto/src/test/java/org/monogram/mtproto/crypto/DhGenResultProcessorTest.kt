package org.monogram.mtproto.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.tl.generated.transport.ClientDhInnerData_42192c855c
import org.monogram.mtproto.tl.generated.transport.DhGenOk
import org.monogram.mtproto.tl.generated.transport.DhGenRetry
import org.monogram.mtproto.tl.generated.transport.PQInnerDataDc
import org.monogram.mtproto.tl.generated.transport.ServerDhInnerData_0c7075057b
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.tl.runtime.TlInt128
import org.monogram.mtproto.tl.runtime.TlInt256

class DhGenResultProcessorTest {
    @Test
    fun acceptsDhGenOkAndProducesFinalMetadata() {
        val pq = pqStage()
        val authKey = ByteArray(256) { it.toByte() }
        val client = client(authKey)
        val hash = MtProtoKeyDerivation.newNonceHash(pq.innerData.newNonce.toByteArray(), authKey, 1)
        val established = DhGenResultProcessor.process(
            pq,
            serverDh(pq),
            client,
            DhGenOk(pq.innerData.nonce, pq.innerData.serverNonce, TlInt128.copyOf(hash)),
        )

        assertArrayEquals(authKey, established.material.toByteArray())
        assertEquals(-3_972_359_982_579_920_590L, established.id)
        assertEquals(1_783_001_185, established.createdAt)
        established.close()
        assertThrows(IllegalStateException::class.java) { established.material.toByteArray() }
        hash.fill(0)
        authKey.fill(0)
    }

    @Test
    fun retryAndHashMismatchDestroyCandidateKey() {
        val pq = pqStage()
        val authKey = ByteArray(256) { it.toByte() }
        val retryClient = client(authKey)
        val retryHash = MtProtoKeyDerivation.newNonceHash(pq.innerData.newNonce.toByteArray(), authKey, 2)
        assertFailure(DhGenFailure.RETRY_REQUESTED) {
            DhGenResultProcessor.process(
                pq,
                serverDh(pq),
                retryClient,
                DhGenRetry(pq.innerData.nonce, pq.innerData.serverNonce, TlInt128.copyOf(retryHash)),
            )
        }
        assertThrows(IllegalStateException::class.java) { retryClient.authKey.toByteArray() }

        val mismatchClient = client(authKey)
        assertFailure(DhGenFailure.NEW_NONCE_HASH_MISMATCH) {
            DhGenResultProcessor.process(
                pq,
                serverDh(pq),
                mismatchClient,
                DhGenOk(pq.innerData.nonce, pq.innerData.serverNonce, TlInt128.copyOf(ByteArray(16))),
            )
        }
        assertThrows(IllegalStateException::class.java) { mismatchClient.authKey.toByteArray() }
        retryHash.fill(0)
        authKey.fill(0)
    }

    private fun pqStage(): PqAuthPrepared = PqAuthPrepared(
        PQInnerDataDc(
            TlBytes.copyOf(byteArrayOf(15)),
            TlBytes.copyOf(byteArrayOf(3)),
            TlBytes.copyOf(byteArrayOf(5)),
            TlInt128.copyOf(ByteArray(16) { it.toByte() }),
            TlInt128.copyOf(ByteArray(16) { (it + 16).toByte() }),
            TlInt256.copyOf(hex("BF8CB5BD9C5B4FE7CF24D64D281F89311576D53C0DA65A83267E57315414C9A6")),
            2,
        ),
        1L,
    )

    private fun serverDh(pq: PqAuthPrepared) = ServerDhInnerData_0c7075057b(
        pq.innerData.nonce,
        pq.innerData.serverNonce,
        3,
        TlBytes.copyOf(byteArrayOf(1)),
        TlBytes.copyOf(byteArrayOf(2)),
        1_783_001_185,
    )

    private fun client(authKey: ByteArray) = ClientDhPrepared(
        ClientDhInnerData_42192c855c(
            TlInt128.copyOf(ByteArray(16)),
            TlInt128.copyOf(ByteArray(16)),
            0,
            TlBytes.copyOf(byteArrayOf(2)),
        ),
        AuthKeyMaterial(authKey),
    )

    private fun assertFailure(expected: DhGenFailure, block: () -> Unit) {
        assertEquals(expected, assertThrows(DhGenException::class.java, block).failure)
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
