package org.monogram.mtproto.handshake

import java.math.BigInteger
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.mtproto.codec.TlBinaryCodec
import org.monogram.mtproto.codec.TlBinaryReader
import org.monogram.mtproto.crypto.AesIge
import org.monogram.mtproto.crypto.DhParameterValidatorTest
import org.monogram.mtproto.crypto.EntropySource
import org.monogram.mtproto.crypto.MtProtoKeyDerivation
import org.monogram.mtproto.crypto.RsaPublicKey
import org.monogram.mtproto.crypto.RsaPublicKeyTest
import org.monogram.mtproto.tl.generated.transport.ClientDhInnerData_42192c855cCodec
import org.monogram.mtproto.tl.generated.transport.DhGenOk
import org.monogram.mtproto.tl.generated.transport.ReqDhParams
import org.monogram.mtproto.tl.generated.transport.ReqPqMulti
import org.monogram.mtproto.tl.generated.transport.ResPq_0c012ada9f
import org.monogram.mtproto.tl.generated.transport.ServerDhInnerData_0c7075057b
import org.monogram.mtproto.tl.generated.transport.ServerDhInnerData_0c7075057bCodec
import org.monogram.mtproto.tl.generated.transport.ServerDhParamsOk
import org.monogram.mtproto.tl.generated.transport.SetClientDhParams
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.tl.runtime.TlDecodeContext
import org.monogram.mtproto.tl.runtime.TlInt128
import org.monogram.mtproto.tl.runtime.TlLimits
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.tl.runtime.TlObject
import org.monogram.mtproto.tl.runtime.TlSchemaIdentity
import org.monogram.mtproto.tl.runtime.TlSchemaKind

class MtProtoAuthHandshakeSuccessTest {
    @Test
    fun completesAllHandshakeStagesAndAgreesOnAuthKey() {
        val entropy = CallCounterEntropy()
        val server = ScriptedServer(entropy)
        try {
            val result = runBlocking {
                MtProtoAuthHandshake(entropy).execute(
                    server,
                    MtProtoHandshakeConfig(2, listOf(RsaPublicKeyTest.PEM), 30_000),
                )
            }
            result.use {
                val actualKey = it.toByteArray()
                try {
                    assertEquals(3, server.requests)
                    assertArrayEquals(server.authKey, actualKey)
                    assertEquals(SERVER_TIME, it.createdAt)
                } finally {
                    actualKey.fill(0)
                }
            }
        } finally {
            server.close()
        }
    }

    private class ScriptedServer(private val entropy: CallCounterEntropy) : MtProtoHandshakeTransport, AutoCloseable {
        private val rsa = RsaPublicKey.fromPkcs1Pem(RsaPublicKeyTest.PEM)
        private val prime = BigInteger(1, DhParameterValidatorTest.PRIME)
        private val serverExponent = BigInteger.ONE.shiftLeft(2040).add(BigInteger.valueOf(12_345))
        private val gA = BigInteger.valueOf(3).modPow(serverExponent, prime)
        private lateinit var nonce: TlInt128
        private val serverNonce = TlInt128.copyOf(ByteArray(16) { (it + 40).toByte() })
        private lateinit var newNonce: ByteArray
        var requests = 0
        lateinit var authKey: ByteArray

        override suspend fun <R : TlObject> execute(method: TlMethod<R>): R {
            requests++
            @Suppress("UNCHECKED_CAST")
            return when (method) {
                is ReqPqMulti -> {
                    nonce = method.nonce
                    ResPq_0c012ada9f(
                        nonce,
                        serverNonce,
                        TlBytes.copyOf(byteArrayOf(15)),
                        listOf(rsa.fingerprint()),
                    ) as R
                }
                is ReqDhParams -> {
                    require(method.nonce == nonce)
                    require(method.serverNonce == serverNonce)
                    require(method.p.isEqualTo(byteArrayOf(3)))
                    require(method.q.isEqualTo(byteArrayOf(5)))
                    require(method.publicKeyFingerprint == rsa.fingerprint())
                    val encryptedData = method.encryptedData.toByteArray()
                    try {
                        require(encryptedData.size == 256)
                    } finally {
                        encryptedData.fill(0)
                    }
                    newNonce = entropy.newNonce()
                    serverDhParams() as R
                }
                is SetClientDhParams -> finish(method) as R
                else -> error("Unexpected method ${method.constructorId}")
            }
        }

        private fun serverDhParams(): ServerDhParamsOk {
            val inner = ServerDhInnerData_0c7075057b(
                nonce,
                serverNonce,
                3,
                TlBytes.copyOf(DhParameterValidatorTest.PRIME),
                TlBytes.copyOf(unsigned(gA)),
                SERVER_TIME,
            )
            val encoded = TlBinaryCodec.encode(ServerDhInnerData_0c7075057bCodec, inner)
            val hash = MessageDigest.getInstance("SHA-1").digest(encoded)
            val padding = ByteArray((16 - ((hash.size + encoded.size) % 16)) % 16) { 0x5a }
            val plaintext = hash + encoded + padding
            val encrypted = try {
                temporaryAes { key, iv -> AesIge.encrypt(plaintext, key, iv) }
            } finally {
                plaintext.fill(0)
                encoded.fill(0)
                hash.fill(0)
                padding.fill(0)
            }
            return try {
                ServerDhParamsOk(nonce, serverNonce, TlBytes.copyOf(encrypted))
            } finally {
                encrypted.fill(0)
            }
        }

        private fun finish(method: SetClientDhParams): DhGenOk {
            require(method.nonce == nonce)
            require(method.serverNonce == serverNonce)
            val encrypted = method.encryptedData.toByteArray()
            val plaintext = try {
                temporaryAes { key, iv -> AesIge.decrypt(encrypted, key, iv) }
            } finally {
                encrypted.fill(0)
            }
            val payload = plaintext.copyOfRange(20, plaintext.size)
            val claimedHash = plaintext.copyOfRange(0, 20)
            val clientInner = try {
                val reader = TlBinaryReader(payload, schema = SCHEMA)
                val decoded = ClientDhInnerData_42192c855cCodec.read(reader, CONTEXT)
                require(reader.remaining in 0..15)
                val actualHash = MessageDigest.getInstance("SHA-1")
                    .apply { update(payload, 0, reader.absoluteOffset.toInt()) }
                    .digest()
                try {
                    require(MessageDigest.isEqual(claimedHash, actualHash))
                } finally {
                    actualHash.fill(0)
                }
                require(decoded.nonce == nonce)
                require(decoded.serverNonce == serverNonce)
                require(decoded.retryId == 0L)
                decoded
            } finally {
                plaintext.fill(0)
                payload.fill(0)
                claimedHash.fill(0)
            }
            val gBBytes = clientInner.gB.toByteArray()
            authKey = try {
                fixed(BigInteger(1, gBBytes).modPow(serverExponent, prime), 256)
            } finally {
                gBBytes.fill(0)
            }
            val hash = MtProtoKeyDerivation.newNonceHash(newNonce, authKey, 1)
            return DhGenOk(nonce, serverNonce, TlInt128.copyOf(hash)).also { hash.fill(0) }
        }

        private fun <T> temporaryAes(block: (ByteArray, ByteArray) -> T): T {
            val serverNonceBytes = serverNonce.toByteArray()
            val material = try {
                MtProtoKeyDerivation.temporaryAesKeyIv(newNonce, serverNonceBytes)
            } finally {
                serverNonceBytes.fill(0)
            }
            val key = material.key
            val iv = material.iv
            material.close()
            return try {
                block(key, iv)
            } finally {
                key.fill(0)
                iv.fill(0)
            }
        }

        override fun close() {
            if (::authKey.isInitialized) authKey.fill(0)
            if (::newNonce.isInitialized) newNonce.fill(0)
            entropy.close()
        }

        private fun unsigned(value: BigInteger): ByteArray = value.toByteArray().let {
            if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
        }

        private fun fixed(value: BigInteger, size: Int): ByteArray {
            val source = unsigned(value)
            return ByteArray(size).also { source.copyInto(it, size - source.size); source.fill(0) }
        }

        private fun TlBytes.isEqualTo(expected: ByteArray): Boolean {
            val actual = toByteArray()
            return try {
                actual.contentEquals(expected)
            } finally {
                actual.fill(0)
            }
        }
    }

    private class CallCounterEntropy : EntropySource, AutoCloseable {
        private var call = 0
        private var capturedNewNonce: ByteArray? = null
        override fun nextBytes(destination: ByteArray) {
            call++
            destination.fill(call.toByte())
            if (call == 2 && destination.size == 32) capturedNewNonce = destination.copyOf()
        }

        fun newNonce(): ByteArray = checkNotNull(capturedNewNonce).also { capturedNewNonce = null }

        override fun close() {
            capturedNewNonce?.fill(0)
            capturedNewNonce = null
        }
    }

    companion object {
        private const val SERVER_TIME = 1_783_001_185
        private val SCHEMA = TlSchemaIdentity(TlSchemaKind.TRANSPORT, null)
        private val CONTEXT = TlDecodeContext(SCHEMA, 0, TlLimits.DEFAULT)
    }
}
