package org.monogram.mtproto.crypto

import java.math.BigInteger

internal data class PqFactors(
    val p: Long,
    val q: Long,
)

internal data class PqFactorizationLimits(
    val maxRestarts: Int = 32,
    val maxIterationsPerRestart: Int = 100_000,
) {
    init {
        require(maxRestarts in 1..32) { "maxRestarts must be within 1..32" }
        require(maxIterationsPerRestart in 1..100_000) {
            "maxIterationsPerRestart must be within 1..100000"
        }
    }
}

internal enum class PqFactorizationFailure {
    INVALID_PQ_ENCODING,
    PQ_OUT_OF_RANGE,
    PQ_NOT_ODD_SEMIPRIME,
    FACTORIZATION_EXHAUSTED,
    FACTORS_NOT_DISTINCT_PRIMES,
    FACTOR_PRODUCT_MISMATCH,
}

internal class PqFactorizationException(
    val failure: PqFactorizationFailure,
) : IllegalArgumentException("PQ factorization failed: $failure")

internal object PqFactorizer {
    fun factor(
        encodedPq: ByteArray,
        entropy: EntropySource = SecureEntropySource,
        limits: PqFactorizationLimits = PqFactorizationLimits(),
    ): PqFactors {
        val pq = parse(encodedPq)
        if (pq.mod(TWO) == BigInteger.ZERO || isPrime(pq)) {
            throw failure(PqFactorizationFailure.PQ_NOT_ODD_SEMIPRIME)
        }

        val divisor = SMALL_PRIMES.firstOrNull { pq.mod(it) == BigInteger.ZERO }
            ?: pollardRho(pq, entropy, limits)
        val other = pq.divide(divisor)
        val p = minOf(divisor, other)
        val q = maxOf(divisor, other)

        if (p == q || !isPrime(p) || !isPrime(q)) {
            throw failure(PqFactorizationFailure.FACTORS_NOT_DISTINCT_PRIMES)
        }
        if (p.multiply(q) != pq) {
            throw failure(PqFactorizationFailure.FACTOR_PRODUCT_MISMATCH)
        }
        return PqFactors(p.longValueExact(), q.longValueExact())
    }

    private fun parse(encodedPq: ByteArray): BigInteger {
        if (encodedPq.isEmpty() || encodedPq.size > Long.SIZE_BYTES || encodedPq.first() == 0.toByte()) {
            throw failure(PqFactorizationFailure.INVALID_PQ_ENCODING)
        }
        val value = BigInteger(1, encodedPq)
        if (value < MIN_PQ || value > LONG_MAX) {
            throw failure(PqFactorizationFailure.PQ_OUT_OF_RANGE)
        }
        return value
    }

    private fun pollardRho(
        value: BigInteger,
        entropy: EntropySource,
        limits: PqFactorizationLimits,
    ): BigInteger {
        repeat(limits.maxRestarts) {
            var x = randomBelow(value.subtract(TWO), entropy).add(TWO)
            var y = x
            val c = randomBelow(value.subtract(ONE), entropy).add(ONE)
            for (iteration in 0 until limits.maxIterationsPerRestart) {
                x = iterate(x, c, value)
                y = iterate(iterate(y, c, value), c, value)
                val divisor = x.subtract(y).abs().gcd(value)
                if (divisor == value) break
                if (divisor > ONE) return divisor
            }
        }
        throw failure(PqFactorizationFailure.FACTORIZATION_EXHAUSTED)
    }

    private fun randomBelow(bound: BigInteger, entropy: EntropySource): BigInteger {
        require(bound > BigInteger.ZERO)
        val bytes = ByteArray((bound.bitLength() + 7) / 8)
        val excessBits = bytes.size * 8 - bound.bitLength()
        repeat(MAX_RANDOM_ATTEMPTS) {
            entropy.nextBytes(bytes)
            bytes[0] = (bytes[0].toInt() and (0xff ushr excessBits)).toByte()
            val candidate = BigInteger(1, bytes)
            if (candidate < bound) {
                bytes.fill(0)
                return candidate
            }
        }
        bytes.fill(0)
        throw failure(PqFactorizationFailure.FACTORIZATION_EXHAUSTED)
    }

    private fun iterate(value: BigInteger, c: BigInteger, modulus: BigInteger): BigInteger =
        value.multiply(value).add(c).mod(modulus)

    private fun isPrime(value: BigInteger): Boolean {
        if (value < TWO) return false
        for (prime in SMALL_PRIMES) {
            if (value == prime) return true
            if (value.mod(prime) == BigInteger.ZERO) return false
        }
        var d = value.subtract(ONE)
        var shifts = 0
        while (!d.testBit(0)) {
            d = d.shiftRight(1)
            shifts++
        }
        return MILLER_RABIN_BASES.all { rawBase ->
            val base = rawBase.mod(value)
            if (base == BigInteger.ZERO) return@all true
            var x = base.modPow(d, value)
            if (x == ONE || x == value.subtract(ONE)) return@all true
            repeat(shifts - 1) {
                x = x.multiply(x).mod(value)
                if (x == value.subtract(ONE)) return@all true
            }
            false
        }
    }

    private fun failure(kind: PqFactorizationFailure) = PqFactorizationException(kind)

    private val ONE = BigInteger.ONE
    private val TWO = BigInteger.valueOf(2)
    private val MIN_PQ = BigInteger.valueOf(15)
    private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
    private val SMALL_PRIMES = listOf(3L, 5L, 7L, 11L, 13L, 17L, 19L, 23L, 29L, 31L)
        .map { BigInteger.valueOf(it) }
    private val MILLER_RABIN_BASES = listOf(
        2L,
        325L,
        9_375L,
        28_178L,
        450_775L,
        9_780_504L,
        1_795_265_022L,
    ).map(BigInteger::valueOf)
    private const val MAX_RANDOM_ATTEMPTS = 128
}
