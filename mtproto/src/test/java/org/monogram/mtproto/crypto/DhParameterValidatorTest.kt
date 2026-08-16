package org.monogram.mtproto.crypto

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.tl.generated.transport.ServerDhInnerData_0c7075057b
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.tl.runtime.TlInt128

class DhParameterValidatorTest {
    @Test
    fun acceptsTelegramBuiltInPrimeAndBoundedGA() {
        val prime = BigInteger(1, PRIME)
        val validated = DhParameterValidator.validate(inner(g = 3, prime = PRIME, gA = unsigned(prime.shiftRight(1))))

        assertEquals(3, validated.generator)
        assertEquals(prime, validated.prime)
        assertEquals(prime.shiftRight(1), validated.gA)
    }

    @Test
    fun rejectsEncodingGeneratorUnsafePrimeAndGAFailures() {
        assertFailure(DhParameterFailure.INVALID_DH_PRIME_ENCODING) {
            DhParameterValidator.validate(inner(prime = PRIME.copyOfRange(1, PRIME.size)))
        }
        assertFailure(DhParameterFailure.INVALID_DH_GENERATOR) {
            DhParameterValidator.validate(inner(g = 8))
        }
        val composite = PRIME.copyOf().also { it[it.lastIndex] = (it.last().toInt() and 0xfe).toByte() }
        assertFailure(DhParameterFailure.UNSAFE_DH_PRIME) {
            DhParameterValidator.validate(inner(g = 4, prime = composite))
        }
        assertFailure(DhParameterFailure.INVALID_G_A_ENCODING) {
            DhParameterValidator.validate(inner(gA = byteArrayOf(0, 2)))
        }
        assertFailure(DhParameterFailure.G_A_OUT_OF_RANGE) {
            DhParameterValidator.validate(inner(gA = byteArrayOf(2)))
        }
    }

    private fun inner(
        g: Int = 3,
        prime: ByteArray = PRIME,
        gA: ByteArray = unsigned(BigInteger(1, PRIME).shiftRight(1)),
    ) = ServerDhInnerData_0c7075057b(
        nonce = TlInt128.copyOf(ByteArray(16)),
        serverNonce = TlInt128.copyOf(ByteArray(16)),
        g = g,
        dhPrime = TlBytes.copyOf(prime),
        gA = TlBytes.copyOf(gA),
        serverTime = 0,
    )

    private fun assertFailure(expected: DhParameterFailure, block: () -> Unit) {
        assertEquals(expected, assertThrows(DhParameterException::class.java, block).failure)
    }

    private fun unsigned(value: BigInteger): ByteArray = value.toByteArray().let {
        if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
    }

    companion object {
        internal val PRIME = hex(
            "c71caeb9c6b1c9048e6c522f70f13f73980d40238e3e21c14934d037563d930f48198a0aa7c14058229493d22530f4dbfa336f6e0ac9" +
                "25139543aed44cce7c3720fd51f69458705ac68cd4fe6b6b13abdc9746512969328454f18faf8c595f642477fe96bb2a941d5bcd1d4a" +
                "c8cc49880708fa9b378e3c4f3a9060bee67cf9a4a4a695811051907e162753b56b0f6b410dba74d8a84b2a14b3144e0ef1284754fd17" +
                "ed950d5965b4b9dd46582db1178d169c6bc465b0d6ff9ca3928fef5b9ae4e418fc15e83ebea0f87fa9ff5eed70050ded2849f47bf959" +
                "d956850ce929851f0d8115f635b105ee2e4e15d04b2454bf6f4fadf034b10403119cd8e3b92fcc5b",
        )

        private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
