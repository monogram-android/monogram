package org.monogram.mtproto.crypto

import java.security.MessageDigest
import org.monogram.mtproto.codec.TlBinaryReader
import org.monogram.mtproto.tl.generated.transport.ServerDhInnerData_0c7075057b
import org.monogram.mtproto.tl.generated.transport.ServerDhInnerData_0c7075057bCodec
import org.monogram.mtproto.tl.generated.transport.ServerDhParamsOk
import org.monogram.mtproto.tl.runtime.TlDecodeContext
import org.monogram.mtproto.tl.runtime.TlLimits
import org.monogram.mtproto.tl.runtime.TlSchemaIdentity
import org.monogram.mtproto.tl.runtime.TlSchemaKind

internal enum class ServerDhParamsFailure {
    NONCE_MISMATCH,
    SERVER_NONCE_MISMATCH,
    INVALID_ENCRYPTED_ANSWER_LENGTH,
    INVALID_INNER_DATA,
    EXCESS_PADDING,
    HASH_MISMATCH,
    INNER_NONCE_MISMATCH,
    INNER_SERVER_NONCE_MISMATCH,
}

internal class ServerDhParamsException(val failure: ServerDhParamsFailure) : IllegalArgumentException(
    "Server DH parameters failed validation: $failure",
)

internal object ServerDhParamsProcessor {
    fun decrypt(
        prepared: PqAuthPrepared,
        response: ServerDhParamsOk,
    ): ServerDhInnerData_0c7075057b {
        val expected = prepared.innerData
        if (response.nonce != expected.nonce) fail(ServerDhParamsFailure.NONCE_MISMATCH)
        if (response.serverNonce != expected.serverNonce) fail(ServerDhParamsFailure.SERVER_NONCE_MISMATCH)
        val encrypted = response.encryptedAnswer.toByteArray()
        if (encrypted.isEmpty() || encrypted.size % 16 != 0) {
            encrypted.fill(0)
            fail(ServerDhParamsFailure.INVALID_ENCRYPTED_ANSWER_LENGTH)
        }
        val newNonce = expected.newNonce.toByteArray()
        val serverNonce = expected.serverNonce.toByteArray()
        val material = try {
            MtProtoKeyDerivation.temporaryAesKeyIv(newNonce, serverNonce)
        } finally {
            newNonce.fill(0)
            serverNonce.fill(0)
        }
        val key = material.key
        val iv = material.iv
        material.close()
        val decrypted = try {
            AesIge.decrypt(encrypted, key, iv)
        } finally {
            encrypted.fill(0)
            key.fill(0)
            iv.fill(0)
        }
        try {
            if (decrypted.size < 20) fail(ServerDhParamsFailure.INVALID_INNER_DATA)
            val claimedHash = decrypted.copyOfRange(0, 20)
            val payload = decrypted.copyOfRange(20, decrypted.size)
            try {
                val reader = TlBinaryReader(payload, absoluteStart = 20, limits = LIMITS, schema = SCHEMA)
                val inner = try {
                    ServerDhInnerData_0c7075057bCodec.read(reader, CONTEXT)
                } catch (_: RuntimeException) {
                    fail(ServerDhParamsFailure.INVALID_INNER_DATA)
                }
                val padding = reader.remaining
                if (padding >= 16) fail(ServerDhParamsFailure.EXCESS_PADDING)
                val objectBytes = payload.copyOfRange(0, payload.size - padding)
                val actualHash = try {
                    MessageDigest.getInstance("SHA-1").digest(objectBytes)
                } finally {
                    objectBytes.fill(0)
                }
                val hashMatches = try {
                    MessageDigest.isEqual(claimedHash, actualHash)
                } finally {
                    actualHash.fill(0)
                }
                if (!hashMatches) fail(ServerDhParamsFailure.HASH_MISMATCH)
                if (inner.nonce != expected.nonce) fail(ServerDhParamsFailure.INNER_NONCE_MISMATCH)
                if (inner.serverNonce != expected.serverNonce) fail(ServerDhParamsFailure.INNER_SERVER_NONCE_MISMATCH)
                return inner
            } finally {
                claimedHash.fill(0)
                payload.fill(0)
            }
        } finally {
            decrypted.fill(0)
        }
    }

    private fun fail(failure: ServerDhParamsFailure): Nothing = throw ServerDhParamsException(failure)

    private val SCHEMA = TlSchemaIdentity(TlSchemaKind.TRANSPORT, null)
    private val LIMITS = TlLimits.DEFAULT.lowered(maxObjectBytes = 4096)
    private val CONTEXT = TlDecodeContext(SCHEMA, 0, LIMITS)
}
