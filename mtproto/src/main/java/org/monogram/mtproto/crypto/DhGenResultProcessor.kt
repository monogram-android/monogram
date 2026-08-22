package org.monogram.mtproto.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.monogram.mtproto.tl.generated.transport.DhGenFail
import org.monogram.mtproto.tl.generated.transport.DhGenOk
import org.monogram.mtproto.tl.generated.transport.DhGenRetry
import org.monogram.mtproto.tl.generated.transport.ServerDhInnerData_0c7075057b
import org.monogram.mtproto.tl.generated.transport.SetClientDhParamsAnswer
import org.monogram.mtproto.tl.runtime.TlInt128

internal enum class DhGenFailure {
    NONCE_MISMATCH,
    SERVER_NONCE_MISMATCH,
    NEW_NONCE_HASH_MISMATCH,
    RETRY_REQUESTED,
    SERVER_REJECTED,
}

internal class DhGenException(val failure: DhGenFailure) : IllegalArgumentException(
    "DH generation failed: $failure",
)

internal data class EstablishedAuthKey(
    val material: AuthKeyMaterial,
    val id: Long,
    val serverSalt: Long,
    val createdAt: Int,
) : AutoCloseable {
    override fun close() = material.close()
}

internal object DhGenResultProcessor {
    fun process(
        pqStage: PqAuthPrepared,
        serverDh: ServerDhInnerData_0c7075057b,
        clientDh: ClientDhPrepared,
        response: SetClientDhParamsAnswer,
    ): EstablishedAuthKey {
        try {
            val (nonce, serverNonce, receivedHash, selector, terminalFailure) = when (response) {
                is DhGenOk -> ResponseFields(response.nonce, response.serverNonce, response.newNonceHash1, 1, null)
                is DhGenRetry -> ResponseFields(
                    response.nonce,
                    response.serverNonce,
                    response.newNonceHash2,
                    2,
                    DhGenFailure.RETRY_REQUESTED,
                )
                is DhGenFail -> ResponseFields(
                    response.nonce,
                    response.serverNonce,
                    response.newNonceHash3,
                    3,
                    DhGenFailure.SERVER_REJECTED,
                )
            }
            if (nonce != pqStage.innerData.nonce) fail(DhGenFailure.NONCE_MISMATCH)
            if (serverNonce != pqStage.innerData.serverNonce) fail(DhGenFailure.SERVER_NONCE_MISMATCH)
            val authKey = clientDh.authKey.toByteArray()
            val newNonce = pqStage.innerData.newNonce.toByteArray()
            try {
                val expectedHash = MtProtoKeyDerivation.newNonceHash(newNonce, authKey, selector)
                val actualHash = receivedHash.toByteArray()
                val matches = try {
                    MessageDigest.isEqual(expectedHash, actualHash)
                } finally {
                    expectedHash.fill(0)
                    actualHash.fill(0)
                }
                if (!matches) fail(DhGenFailure.NEW_NONCE_HASH_MISMATCH)
                terminalFailure?.let(::fail)
                val idBytes = MtProtoKeyDerivation.authKeyIdBytes(authKey)
                val serverNonceBytes = pqStage.innerData.serverNonce.toByteArray()
                val saltBytes = try {
                    MtProtoKeyDerivation.initialServerSalt(newNonce, serverNonceBytes)
                } finally {
                    serverNonceBytes.fill(0)
                }
                return try {
                    EstablishedAuthKey(
                        material = clientDh.authKey,
                        id = littleEndianLong(idBytes),
                        serverSalt = littleEndianLong(saltBytes),
                        createdAt = serverDh.serverTime,
                    )
                } finally {
                    idBytes.fill(0)
                    saltBytes.fill(0)
                }
            } finally {
                authKey.fill(0)
                newNonce.fill(0)
            }
        } catch (failure: RuntimeException) {
            clientDh.authKey.close()
            throw failure
        }
    }

    private fun littleEndianLong(bytes: ByteArray): Long {
        require(bytes.size == Long.SIZE_BYTES)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long
    }

    private fun fail(failure: DhGenFailure): Nothing = throw DhGenException(failure)

    private data class ResponseFields(
        val nonce: TlInt128,
        val serverNonce: TlInt128,
        val hash: TlInt128,
        val selector: Int,
        val terminalFailure: DhGenFailure?,
    )
}
