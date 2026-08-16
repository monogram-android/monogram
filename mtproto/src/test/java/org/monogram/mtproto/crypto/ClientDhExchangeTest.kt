package org.monogram.mtproto.crypto

import java.math.BigInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.tl.generated.transport.PQInnerDataDc
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.tl.runtime.TlInt128
import org.monogram.mtproto.tl.runtime.TlInt256

class ClientDhExchangeTest {
    @Test
    fun generatesBoundedClientValueAuthKeyAndEncryptedRequest() {
        val prime = BigInteger(1, DhParameterValidatorTest.PRIME)
        val parameters = ValidatedDhParameters(3, prime, prime.shiftRight(1))
        val pq = prepared()
        val entropy = XorShiftEntropy()

        val client = ClientDhExchange.generate(pq, parameters, entropy)
        val gB = BigInteger(1, client.innerData.gB.toByteArray())
        val margin = BigInteger.ONE.shiftLeft(2048 - 64)
        assertEquals(0L, client.innerData.retryId)
        assertFalse(gB < margin)
        assertFalse(gB > prime.subtract(margin))
        assertEquals(256, client.authKey.toByteArray().size)

        val request = ClientDhExchange.buildRequest(pq, client, entropy)
        assertEquals(pq.innerData.nonce, request.nonce)
        assertEquals(pq.innerData.serverNonce, request.serverNonce)
        assertEquals(0, request.encryptedData.toByteArray().size % 16)
        client.authKey.close()
        assertThrows(IllegalStateException::class.java) { client.authKey.toByteArray() }
    }

    @Test
    fun authKeyUsesDefensiveCopies() {
        val source = ByteArray(256) { it.toByte() }
        val material = AuthKeyMaterial(source)
        source.fill(0)
        val first = material.toByteArray()
        val second = material.toByteArray()
        first.fill(0)
        assertFalse(second.all { it == 0.toByte() })
        assertArrayEquals(ByteArray(256) { it.toByte() }, second)
        material.close()
    }

    private fun prepared(): PqAuthPrepared = PqAuthPrepared(
        PQInnerDataDc(
            pq = TlBytes.copyOf(byteArrayOf(15)),
            p = TlBytes.copyOf(byteArrayOf(3)),
            q = TlBytes.copyOf(byteArrayOf(5)),
            nonce = TlInt128.copyOf(ByteArray(16) { it.toByte() }),
            serverNonce = TlInt128.copyOf(ByteArray(16) { (it + 16).toByte() }),
            newNonce = TlInt256.copyOf(ByteArray(32) { (it + 32).toByte() }),
            dc = 2,
        ),
        1L,
    )

    private class XorShiftEntropy : EntropySource {
        private var state = 0x510e527fade682d1uL
        override fun nextBytes(destination: ByteArray) {
            destination.indices.forEach { index ->
                state = state xor (state shl 13)
                state = state xor (state shr 7)
                state = state xor (state shl 17)
                destination[index] = state.toByte()
            }
        }
    }
}
