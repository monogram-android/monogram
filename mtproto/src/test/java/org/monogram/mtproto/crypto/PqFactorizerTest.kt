package org.monogram.mtproto.crypto

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PqFactorizerTest {
    @Test
    fun `official Telegram pq factors match published values`() {
        val factors = PqFactorizer.factor(
            encodedPq = hex("2E9CDB98C80CDA4B"),
            entropy = CounterEntropySource(),
        )

        assertEquals(1_786_331_737L, factors.p)
        assertEquals(1_880_278_339L, factors.q)
    }

    @Test
    fun `small and generated semiprimes return ordered exact prime factors`() {
        val pairs = listOf(3L to 5L, 101L to 103L, 65_521L to 65_537L, 2_147_483_629L to 2_147_483_647L)
        pairs.forEach { (p, q) ->
            val product = BigInteger.valueOf(p).multiply(BigInteger.valueOf(q))
            val factors = PqFactorizer.factor(product.toByteArray().dropWhile { it == 0.toByte() }.toByteArray(), CounterEntropySource())
            assertEquals(p, factors.p)
            assertEquals(q, factors.q)
            assertEquals(product, BigInteger.valueOf(factors.p).multiply(BigInteger.valueOf(factors.q)))
        }
    }

    @Test
    fun `invalid encodings primes squares and even values are rejected`() {
        assertFailure(PqFactorizationFailure.INVALID_PQ_ENCODING) { PqFactorizer.factor(ByteArray(0)) }
        assertFailure(PqFactorizationFailure.INVALID_PQ_ENCODING) { PqFactorizer.factor(byteArrayOf(0, 15)) }
        assertFailure(PqFactorizationFailure.INVALID_PQ_ENCODING) { PqFactorizer.factor(ByteArray(9) { 1 }) }
        assertFailure(PqFactorizationFailure.PQ_OUT_OF_RANGE) { PqFactorizer.factor(byteArrayOf(14)) }
        assertFailure(PqFactorizationFailure.PQ_OUT_OF_RANGE) { PqFactorizer.factor(hex("8000000000000001")) }
        assertFailure(PqFactorizationFailure.PQ_NOT_ODD_SEMIPRIME) { PqFactorizer.factor(byteArrayOf(16)) }
        assertFailure(PqFactorizationFailure.PQ_NOT_ODD_SEMIPRIME) { PqFactorizer.factor(byteArrayOf(17)) }
        assertFailure(PqFactorizationFailure.FACTORS_NOT_DISTINCT_PRIMES) { PqFactorizer.factor(byteArrayOf(25)) }
    }

    @Test
    fun `encoded input is not mutated`() {
        val encoded = hex("2E9CDB98C80CDA4B")
        val original = encoded.copyOf()
        PqFactorizer.factor(encoded, CounterEntropySource())
        org.junit.Assert.assertArrayEquals(original, encoded)
    }

    @Test
    fun `factorization budget exhaustion is typed and bounded`() {
        assertFailure(PqFactorizationFailure.FACTORIZATION_EXHAUSTED) {
            PqFactorizer.factor(
                encodedPq = hex("2E9CDB98C80CDA4B"),
                entropy = CounterEntropySource(),
                limits = PqFactorizationLimits(maxRestarts = 1, maxIterationsPerRestart = 1),
            )
        }
    }

    private fun assertFailure(expected: PqFactorizationFailure, block: () -> Unit) {
        val failure = assertThrows(PqFactorizationException::class.java, block)
        assertEquals(expected, failure.failure)
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private class CounterEntropySource : EntropySource {
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
}
