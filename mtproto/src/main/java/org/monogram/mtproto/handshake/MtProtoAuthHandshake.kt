package org.monogram.mtproto.handshake

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.monogram.mtproto.crypto.ClientDhExchange
import org.monogram.mtproto.crypto.DhGenResultProcessor
import org.monogram.mtproto.crypto.DhParameterValidator
import org.monogram.mtproto.crypto.AuthKeyMaterial
import org.monogram.mtproto.crypto.EntropySource
import org.monogram.mtproto.crypto.EstablishedAuthKey
import org.monogram.mtproto.crypto.MtProtoKeyDerivation
import org.monogram.mtproto.crypto.PqAuthStage
import org.monogram.mtproto.crypto.RsaPublicKey
import org.monogram.mtproto.crypto.SecureEntropySource
import org.monogram.mtproto.crypto.ServerDhParamsProcessor
import org.monogram.mtproto.tl.generated.transport.ResPq_0c012ada9f
import org.monogram.mtproto.tl.generated.transport.ServerDhParamsOk
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.tl.runtime.TlObject

interface MtProtoHandshakeTransport {
    suspend fun <R : TlObject> execute(method: TlMethod<R>): R
}

data class MtProtoHandshakeConfig(
    val dcId: Int,
    val serverRsaPublicKeys: List<String>,
    val timeoutMillis: Long = 30_000,
) {
    init {
        require(dcId > 0) { "dcId must be positive" }
        require(serverRsaPublicKeys.isNotEmpty()) { "At least one server RSA public key is required" }
        require(timeoutMillis in 1..120_000) { "timeoutMillis must be within 1..120000" }
    }
}

enum class MtProtoHandshakeFailure {
    TIMEOUT,
    TRANSPORT,
    UNSUPPORTED_RESPONSE,
    PROTOCOL_VALIDATION,
}

class MtProtoHandshakeException(
    val failure: MtProtoHandshakeFailure,
    cause: Throwable? = null,
) : IllegalStateException("MTProto auth handshake failed: $failure", cause)

class MtProtoAuthKey internal constructor(
    private val established: org.monogram.mtproto.crypto.EstablishedAuthKey,
) : AutoCloseable {
    val id: Long get() = established.id
    val serverSalt: Long get() = established.serverSalt
    val createdAt: Int get() = established.createdAt
    fun toByteArray(): ByteArray = established.material.toByteArray()
    override fun close() = established.close()

    companion object {
        const val MATERIAL_BYTES = 256

        fun restore(material: ByteArray, id: Long, serverSalt: Long, createdAt: Int): MtProtoAuthKey {
            require(material.size == MATERIAL_BYTES) { "MTProto auth key must contain 256 bytes" }
            val idBytes = MtProtoKeyDerivation.authKeyIdBytes(material)
            val calculatedId = try {
                java.nio.ByteBuffer.wrap(idBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).long
            } finally {
                idBytes.fill(0)
            }
            require(calculatedId == id) { "MTProto auth key ID mismatch" }
            return MtProtoAuthKey(
                EstablishedAuthKey(
                    material = AuthKeyMaterial(material),
                    id = id,
                    serverSalt = serverSalt,
                    createdAt = createdAt,
                ),
            )
        }
    }
}

class MtProtoAuthHandshake internal constructor(
    private val entropy: EntropySource,
) {
    constructor() : this(SecureEntropySource)

    suspend fun execute(
        transport: MtProtoHandshakeTransport,
        config: MtProtoHandshakeConfig,
    ): MtProtoAuthKey = try {
        withTimeout(config.timeoutMillis) {
            runHandshake(transport, config)
        }
    } catch (timeout: TimeoutCancellationException) {
        throw MtProtoHandshakeException(MtProtoHandshakeFailure.TIMEOUT, timeout)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (known: MtProtoHandshakeException) {
        throw known
    } catch (failure: RuntimeException) {
        throw MtProtoHandshakeException(MtProtoHandshakeFailure.PROTOCOL_VALIDATION, failure)
    }

    private suspend fun runHandshake(
        transport: MtProtoHandshakeTransport,
        config: MtProtoHandshakeConfig,
    ): MtProtoAuthKey {
        val keys = withContext(Dispatchers.Default) {
            config.serverRsaPublicKeys.map(RsaPublicKey::fromPkcs1Pem)
        }
        val keysByFingerprint = keys.associateBy(RsaPublicKey::fingerprint)
        require(keysByFingerprint.size == keys.size) { "Duplicate RSA key fingerprints" }
        val pqRequest = PqAuthStage.createRequest(entropy)
        val resPq = invokeTransport(transport, pqRequest.request) as? ResPq_0c012ada9f
            ?: unsupported()
        val pqPrepared = withContext(Dispatchers.Default) {
            PqAuthStage.prepare(
                pqRequest,
                resPq,
                keysByFingerprint.keys,
                config.dcId,
                entropy,
            )
        }
        val rsaKey = keysByFingerprint.getValue(pqPrepared.rsaFingerprint)
        val reqDh = withContext(Dispatchers.Default) {
            PqAuthStage.buildDhParamsRequest(pqPrepared, rsaKey, entropy)
        }
        val serverDhParams = invokeTransport(transport, reqDh) as? ServerDhParamsOk ?: unsupported()
        val serverDh = withContext(Dispatchers.Default) {
            ServerDhParamsProcessor.decrypt(pqPrepared, serverDhParams)
        }
        val validated = withContext(Dispatchers.Default) { DhParameterValidator.validate(serverDh) }
        val clientDh = withContext(Dispatchers.Default) {
            ClientDhExchange.generate(pqPrepared, validated, entropy)
        }
        try {
            val setClientDh = withContext(Dispatchers.Default) {
                ClientDhExchange.buildRequest(pqPrepared, clientDh, entropy)
            }
            val terminal = invokeTransport(transport, setClientDh)
            return MtProtoAuthKey(DhGenResultProcessor.process(pqPrepared, serverDh, clientDh, terminal))
        } catch (failure: Throwable) {
            clientDh.authKey.close()
            throw failure
        }
    }

    private suspend fun <R : TlObject> invokeTransport(
        transport: MtProtoHandshakeTransport,
        method: TlMethod<R>,
    ): R = try {
        transport.execute(method)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw MtProtoHandshakeException(MtProtoHandshakeFailure.TRANSPORT, failure)
    }

    private fun unsupported(): Nothing = throw MtProtoHandshakeException(MtProtoHandshakeFailure.UNSUPPORTED_RESPONSE)
}
