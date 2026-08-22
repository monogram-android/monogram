package org.monogram.mtproto.crypto

import java.math.BigInteger
import java.security.MessageDigest
import org.monogram.mtproto.codec.TlBinaryCodec
import org.monogram.mtproto.tl.generated.transport.ClientDhInnerData_42192c855c
import org.monogram.mtproto.tl.generated.transport.ClientDhInnerData_42192c855cCodec
import org.monogram.mtproto.tl.generated.transport.SetClientDhParams
import org.monogram.mtproto.tl.runtime.TlBytes

internal class AuthKeyMaterial(bytes: ByteArray) : AutoCloseable {
    private val value = bytes.copyOf()
    private var destroyed = false
    fun toByteArray(): ByteArray {
        check(!destroyed) { "Auth key material has been destroyed" }
        return value.copyOf()
    }
    override fun close() {
        value.fill(0)
        destroyed = true
    }
}

internal data class ClientDhPrepared(
    val innerData: ClientDhInnerData_42192c855c,
    val authKey: AuthKeyMaterial,
)

internal object ClientDhExchange {
    fun generate(
        pqStage: PqAuthPrepared,
        parameters: ValidatedDhParameters,
        entropy: EntropySource = SecureEntropySource,
        maxAttempts: Int = 32,
    ): ClientDhPrepared {
        require(maxAttempts in 1..32) { "maxAttempts must be within 1..32" }
        val lowerBound = ONE.shiftLeft(2048 - 64)
        repeat(maxAttempts) {
            val exponentBytes = ByteArray(256)
            val exponent = try {
                entropy.nextBytes(exponentBytes)
                BigInteger(1, exponentBytes)
            } finally {
                exponentBytes.fill(0)
            }
            if (exponent < TWO || exponent > parameters.prime.subtract(TWO)) return@repeat
            val gB = BigInteger.valueOf(parameters.generator.toLong()).modPow(exponent, parameters.prime)
            if (gB < lowerBound || gB > parameters.prime.subtract(lowerBound)) return@repeat
            val authKeyBytes = fixed(parameters.gA.modPow(exponent, parameters.prime), 256)
            val gBBytes = unsigned(gB)
            return try {
                ClientDhPrepared(
                    ClientDhInnerData_42192c855c(
                        nonce = pqStage.innerData.nonce,
                        serverNonce = pqStage.innerData.serverNonce,
                        retryId = 0,
                        gB = TlBytes.copyOf(gBBytes),
                    ),
                    AuthKeyMaterial(authKeyBytes),
                )
            } finally {
                authKeyBytes.fill(0)
                gBBytes.fill(0)
            }
        }
        throw IllegalStateException("Unable to generate bounded client DH values")
    }

    fun buildRequest(
        pqStage: PqAuthPrepared,
        prepared: ClientDhPrepared,
        entropy: EntropySource = SecureEntropySource,
    ): SetClientDhParams {
        val encoded = TlBinaryCodec.encode(ClientDhInnerData_42192c855cCodec, prepared.innerData)
        val hash = MessageDigest.getInstance("SHA-1").digest(encoded)
        val unpaddedSize = hash.size + encoded.size
        val paddedSize = (unpaddedSize + 15) and -16
        val plaintext = ByteArray(paddedSize)
        hash.copyInto(plaintext)
        encoded.copyInto(plaintext, hash.size)
        if (paddedSize > unpaddedSize) {
            val padding = ByteArray(paddedSize - unpaddedSize)
            try {
                entropy.nextBytes(padding)
                padding.copyInto(plaintext, unpaddedSize)
            } finally {
                padding.fill(0)
            }
        }
        val newNonce = pqStage.innerData.newNonce.toByteArray()
        val serverNonce = pqStage.innerData.serverNonce.toByteArray()
        val material = try {
            MtProtoKeyDerivation.temporaryAesKeyIv(newNonce, serverNonce)
        } finally {
            newNonce.fill(0)
            serverNonce.fill(0)
        }
        val key = material.key
        val iv = material.iv
        material.close()
        val encrypted = try {
            AesIge.encrypt(plaintext, key, iv)
        } finally {
            key.fill(0)
            iv.fill(0)
            plaintext.fill(0)
            encoded.fill(0)
            hash.fill(0)
        }
        return SetClientDhParams(
            nonce = pqStage.innerData.nonce,
            serverNonce = pqStage.innerData.serverNonce,
            encryptedData = TlBytes.copyOf(encrypted),
        ).also { encrypted.fill(0) }
    }

    private fun unsigned(value: BigInteger): ByteArray = value.toByteArray().let {
        if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
    }

    private fun fixed(value: BigInteger, size: Int): ByteArray {
        val source = unsigned(value)
        require(source.size <= size)
        return ByteArray(size).also { source.copyInto(it, size - source.size); source.fill(0) }
    }

    private val ONE = BigInteger.ONE
    private val TWO = BigInteger.valueOf(2)
}
