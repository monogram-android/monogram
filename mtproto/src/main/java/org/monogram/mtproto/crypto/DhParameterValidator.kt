package org.monogram.mtproto.crypto

import java.math.BigInteger
import org.monogram.mtproto.tl.generated.transport.ServerDhInnerData_0c7075057b

internal enum class DhParameterFailure {
    INVALID_DH_PRIME_ENCODING,
    INVALID_DH_GENERATOR,
    UNSAFE_DH_PRIME,
    INVALID_G_A_ENCODING,
    G_A_OUT_OF_RANGE,
}

internal class DhParameterException(val failure: DhParameterFailure) : IllegalArgumentException(
    "DH parameters failed validation: $failure",
)

internal data class ValidatedDhParameters(
    val generator: Int,
    val prime: BigInteger,
    val gA: BigInteger,
)

internal object DhParameterValidator {
    fun validate(inner: ServerDhInnerData_0c7075057b): ValidatedDhParameters {
        val primeBytes = inner.dhPrime.toByteArray()
        val gABytes = inner.gA.toByteArray()
        try {
            val prime = validatePrime(inner.g, primeBytes)
            if (gABytes.isEmpty() || gABytes.size > PRIME_BYTES || gABytes[0] == 0.toByte()) {
                fail(DhParameterFailure.INVALID_G_A_ENCODING)
            }
            val gA = BigInteger(1, gABytes)
            val margin = ONE.shiftLeft(PRIME_BITS - SAFETY_BITS)
            if (gA < margin || gA > prime.subtract(margin)) fail(DhParameterFailure.G_A_OUT_OF_RANGE)
            return ValidatedDhParameters(inner.g, prime, gA)
        } finally {
            primeBytes.fill(0)
            gABytes.fill(0)
        }
    }

    fun validatePrime(generator: Int, primeBytes: ByteArray): BigInteger {
        if (primeBytes.size != PRIME_BYTES || primeBytes[0].toInt() and 0x80 == 0) {
            fail(DhParameterFailure.INVALID_DH_PRIME_ENCODING)
        }
        val prime = BigInteger(1, primeBytes)
        validateGenerator(generator, prime)
        if (!primeBytes.contentEquals(KNOWN_GOOD_PRIME) &&
            (!prime.isProbablePrime(PRIME_CERTAINTY) || !prime.subtract(ONE).shiftRight(1).isProbablePrime(PRIME_CERTAINTY))
        ) {
            fail(DhParameterFailure.UNSAFE_DH_PRIME)
        }
        return prime
    }

    private fun validateGenerator(generator: Int, prime: BigInteger) {
        val valid = when (generator) {
            2 -> prime.mod(BigInteger.valueOf(8)) == BigInteger.valueOf(7)
            3 -> prime.mod(BigInteger.valueOf(3)) == TWO
            4 -> true
            5 -> prime.mod(BigInteger.valueOf(5)).let { it == ONE || it == BigInteger.valueOf(4) }
            6 -> prime.mod(BigInteger.valueOf(24)).let { it == BigInteger.valueOf(19) || it == BigInteger.valueOf(23) }
            7 -> prime.mod(BigInteger.valueOf(7)).let {
                it == BigInteger.valueOf(3) || it == BigInteger.valueOf(5) || it == BigInteger.valueOf(6)
            }
            else -> false
        }
        if (!valid) fail(DhParameterFailure.INVALID_DH_GENERATOR)
    }

    private fun fail(failure: DhParameterFailure): Nothing = throw DhParameterException(failure)

    private val ONE = BigInteger.ONE
    private val TWO = BigInteger.valueOf(2)
    private val KNOWN_GOOD_PRIME = hex(
        "c71caeb9c6b1c9048e6c522f70f13f73980d40238e3e21c14934d037563d930f48198a0aa7c14058229493d22530f4dbfa336f6e0ac9" +
            "25139543aed44cce7c3720fd51f69458705ac68cd4fe6b6b13abdc9746512969328454f18faf8c595f642477fe96bb2a941d5bcd1d4a" +
            "c8cc49880708fa9b378e3c4f3a9060bee67cf9a4a4a695811051907e162753b56b0f6b410dba74d8a84b2a14b3144e0ef1284754fd17" +
            "ed950d5965b4b9dd46582db1178d169c6bc465b0d6ff9ca3928fef5b9ae4e418fc15e83ebea0f87fa9ff5eed70050ded2849f47bf959" +
            "d956850ce929851f0d8115f635b105ee2e4e15d04b2454bf6f4fadf034b10403119cd8e3b92fcc5b",
    )

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private const val PRIME_BYTES = 256
    private const val PRIME_BITS = 2048
    private const val SAFETY_BITS = 64
    private const val PRIME_CERTAINTY = 80
}
