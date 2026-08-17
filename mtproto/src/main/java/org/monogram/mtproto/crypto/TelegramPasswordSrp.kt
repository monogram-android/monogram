package org.monogram.mtproto.crypto

import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal enum class TelegramPasswordSrpFailure {
    INVALID_SERVER_B_ENCODING,
    SERVER_B_OUT_OF_RANGE,
}

internal class TelegramPasswordSrpException(
    val failure: TelegramPasswordSrpFailure,
) : IllegalArgumentException("Telegram password SRP parameters failed validation: $failure")

internal data class TelegramPasswordSrpProof(
    val srpId: Long,
    val a: ByteArray,
    val m1: ByteArray,
)

internal object TelegramPasswordSrp {
    fun createProof(
        password: String,
        salt1: ByteArray,
        salt2: ByteArray,
        generator: Int,
        primeBytes: ByteArray,
        serverBBytes: ByteArray,
        srpId: Long,
        entropy: EntropySource = SecureEntropySource,
    ): TelegramPasswordSrpProof {
        require(password.isNotEmpty()) { "password must not be empty" }
        val prime = DhParameterValidator.validatePrime(generator, primeBytes)
        validateServerB(serverBBytes, prime)

        val temporary = mutableListOf<ByteArray>()
        fun tracked(value: ByteArray): ByteArray = value.also(temporary::add)

        return try {
            val passwordBytes = tracked(password.toByteArray(Charsets.UTF_8))
            val firstPasswordHash = tracked(saltedHash(passwordBytes, salt1))
            val ph1 = tracked(saltedHash(firstPasswordHash, salt2))
            val pbkdf2 = tracked(pbkdf2HmacSha512(ph1, salt1))
            val xBytes = tracked(saltedHash(pbkdf2, salt2))
            val x = BigInteger(1, xBytes)

            val exponentBytes = tracked(ByteArray(PADDED_BYTES).also(entropy::nextBytes))
            val exponent = BigInteger(1, exponentBytes)
            val generatorValue = BigInteger.valueOf(generator.toLong())
            val generatorPadded = tracked(generatorValue.toUnsignedFixed(PADDED_BYTES))
            val clientA = generatorValue.modPow(exponent, prime).toUnsignedFixed(PADDED_BYTES)
            val serverB = BigInteger(1, serverBBytes)
            val serverBPadded = tracked(serverB.toUnsignedFixed(PADDED_BYTES))

            val uBytes = tracked(sha256(clientA, serverBPadded))
            val u = BigInteger(1, uBytes)
            val kBytes = tracked(sha256(primeBytes, generatorPadded))
            val k = BigInteger(1, kBytes)
            val verifier = generatorValue.modPow(x, prime)
            val base = serverB.subtract(k.multiply(verifier).mod(prime)).mod(prime)
            val sharedSecret = base.modPow(exponent.add(u.multiply(x)), prime)
            val sharedSecretPadded = tracked(sharedSecret.toUnsignedFixed(PADDED_BYTES))
            val sessionKey = tracked(sha256(sharedSecretPadded))

            val primeHash = tracked(sha256(primeBytes))
            val generatorHash = tracked(sha256(generatorPadded))
            val primeGeneratorHash = tracked(
                ByteArray(primeHash.size) { (primeHash[it].toInt() xor generatorHash[it].toInt()).toByte() },
            )
            val salt1Hash = tracked(sha256(salt1))
            val salt2Hash = tracked(sha256(salt2))
            val m1 = sha256(
                primeGeneratorHash,
                salt1Hash,
                salt2Hash,
                clientA,
                serverBPadded,
                sessionKey,
            )
            TelegramPasswordSrpProof(srpId, clientA, m1)
        } finally {
            temporary.forEach { it.fill(0) }
        }
    }

    private fun validateServerB(serverBBytes: ByteArray, prime: BigInteger) {
        if (serverBBytes.size !in MIN_SERVER_B_BYTES..PADDED_BYTES) {
            fail(TelegramPasswordSrpFailure.INVALID_SERVER_B_ENCODING)
        }
        val serverB = BigInteger(1, serverBBytes)
        if (serverB.signum() <= 0 || serverB >= prime) {
            fail(TelegramPasswordSrpFailure.SERVER_B_OUT_OF_RANGE)
        }
    }

    private fun saltedHash(data: ByteArray, salt: ByteArray): ByteArray = sha256(salt, data, salt)

    private fun pbkdf2HmacSha512(password: ByteArray, salt: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA512).apply {
            init(SecretKeySpec(password, HMAC_SHA512))
        }
        val blockIndex = byteArrayOf(0, 0, 0, 1)
        val initialInput = concatenate(salt, blockIndex)
        var iteration = try {
            mac.doFinal(initialInput)
        } finally {
            initialInput.fill(0)
        }
        val result = iteration.copyOf()
        try {
            repeat(PBKDF2_ITERATIONS - 1) {
                val next = mac.doFinal(iteration)
                iteration.fill(0)
                iteration = next
                for (index in result.indices) {
                    result[index] = (result[index].toInt() xor iteration[index].toInt()).toByte()
                }
            }
            return result
        } finally {
            iteration.fill(0)
            blockIndex.fill(0)
        }
    }

    private fun sha256(vararg values: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach(digest::update)
        return digest.digest()
    }

    private fun concatenate(first: ByteArray, second: ByteArray): ByteArray =
        ByteArray(first.size + second.size).also { result ->
            first.copyInto(result)
            second.copyInto(result, first.size)
        }

    private fun BigInteger.toUnsignedFixed(size: Int): ByteArray {
        val encoded = toByteArray()
        val unsigned = if (encoded.size > 1 && encoded[0] == 0.toByte()) encoded.copyOfRange(1, encoded.size) else encoded
        require(unsigned.size <= size) { "Integer does not fit in $size bytes" }
        return ByteArray(size).also { unsigned.copyInto(it, size - unsigned.size) }
    }

    private fun fail(failure: TelegramPasswordSrpFailure): Nothing = throw TelegramPasswordSrpException(failure)

    private const val PADDED_BYTES = 256
    private const val MIN_SERVER_B_BYTES = 248
    private const val PBKDF2_ITERATIONS = 100_000
    private const val HMAC_SHA512 = "HmacSHA512"
}
