package org.monogram.mtproto.crypto

import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64

internal class RsaPublicKey private constructor(
    private val modulus: BigInteger,
    private val exponent: BigInteger,
) {
    fun fingerprint(): Long {
        val encoded = tlBytes(modulusBytes(), exponentBytes())
        val digest = MessageDigest.getInstance("SHA-1").digest(encoded)
        var value = 0L
        for (index in 0 until 8) value = value or ((digest[12 + index].toLong() and 0xff) shl (index * 8))
        return value
    }

    fun encryptRaw(input: ByteArray): ByteArray {
        require(input.size == MODULUS_BYTES) { "RSA input must be exactly 256 bytes" }
        return requireNotNull(encryptRawOrNull(input)) { "RSA input must be smaller than modulus" }
    }

    fun encryptRawOrNull(input: ByteArray): ByteArray? {
        require(input.size == MODULUS_BYTES) { "RSA input must be exactly 256 bytes" }
        val value = BigInteger(1, input)
        if (value >= modulus) return null
        return toFixed(value.modPow(exponent, modulus), MODULUS_BYTES)
    }

    private fun modulusBytes() = toFixed(modulus, MODULUS_BYTES)
    private fun exponentBytes() = exponent.toByteArray().let { bytes ->
        if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }

    companion object {
        private const val MODULUS_BYTES = 256

        fun fromPkcs1Pem(pem: String): RsaPublicKey {
            require(pem.contains("-----BEGIN RSA PUBLIC KEY-----")) { "PKCS#1 RSA public key header missing" }
            require(pem.contains("-----END RSA PUBLIC KEY-----")) { "PKCS#1 RSA public key footer missing" }
            val body = pem.substringAfter("-----BEGIN RSA PUBLIC KEY-----")
                .substringBefore("-----END RSA PUBLIC KEY-----")
                .filterNot(Char::isWhitespace)
            val der = Base64.getDecoder().decode(body)
            return try {
                parseDer(der)
            } finally {
                der.fill(0)
            }
        }

        private fun parseDer(bytes: ByteArray): RsaPublicKey {
            val reader = DerReader(bytes)
            require(reader.readByte() == 0x30) { "RSA key must be a DER sequence" }
            val sequenceLength = reader.readLength()
            require(sequenceLength == reader.remaining) { "RSA sequence length mismatch" }
            val n = reader.readInteger()
            val e = reader.readInteger()
            require(reader.remaining == 0) { "RSA key has trailing data" }
            require(n.bitLength() in 2041..2048) { "RSA modulus must be 2048-bit" }
            require(e > ONE && e.testBit(0)) { "RSA exponent must be odd and greater than one" }
            return RsaPublicKey(n, e)
        }

        private fun toFixed(value: BigInteger, size: Int): ByteArray {
            val source = value.toByteArray()
            val offset = if (source.size > 1 && source[0] == 0.toByte()) 1 else 0
            require(source.size - offset <= size)
            return ByteArray(size).also { source.copyInto(it, size - (source.size - offset), offset) }
        }

        private fun tlBytes(vararg values: ByteArray): ByteArray {
            val out = ArrayList<Byte>()
            values.forEach { value ->
                if (value.size < 254) {
                    out += value.size.toByte()
                } else {
                    require(value.size < 1 shl 24)
                    out += 254.toByte()
                    out += (value.size and 0xff).toByte()
                    out += ((value.size ushr 8) and 0xff).toByte()
                    out += ((value.size ushr 16) and 0xff).toByte()
                }
                out.addAll(value.toList())
                val headerSize = if (value.size < 254) 1 else 4
                repeat((4 - ((headerSize + value.size) % 4)) % 4) { out += 0 }
            }
            return out.toByteArray()
        }

        private val ONE = BigInteger.ONE
    }

    private class DerReader(private val bytes: ByteArray) {
        private var offset = 0
        val remaining: Int get() = bytes.size - offset
        fun readByte(): Int {
            require(remaining > 0) { "truncated DER value" }
            return bytes[offset++].toInt() and 0xff
        }
        fun readLength(): Int {
            val first = readByte()
            if (first < 0x80) return first
            require(first in 0x81..0x84) { "unsupported DER length" }
            val count = first and 0x7f
            require(count <= 4 && remaining >= count)
            var result = 0
            repeat(count) { result = (result shl 8) or readByte() }
            require(result >= 0x80) { "non-canonical DER length" }
            return result
        }
        fun readInteger(): BigInteger {
            require(readByte() == 0x02) { "RSA key field must be an INTEGER" }
            val length = readLength()
            require(length > 0 && length <= remaining)
            val value = bytes.copyOfRange(offset, offset + length)
            offset += length
            require(value[0].toInt() and 0x80 == 0) { "negative RSA integer" }
            require(length == 1 || value[0] != 0.toByte() || value[1].toInt() and 0x80 != 0) {
                "non-canonical RSA integer"
            }
            return BigInteger(1, value)
        }
    }
}
