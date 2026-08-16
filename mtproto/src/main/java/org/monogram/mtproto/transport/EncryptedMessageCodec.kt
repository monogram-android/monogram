package org.monogram.mtproto.transport

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.monogram.mtproto.crypto.AesIge
import org.monogram.mtproto.crypto.EntropySource
import org.monogram.mtproto.crypto.MtProtoKeyDerivation
import org.monogram.mtproto.handshake.MtProtoAuthKey

data class MtProtoEncryptedMessageMetadata(
    val serverSalt: Long,
    val sessionId: Long,
    val messageId: Long,
    val sequenceNumber: Int,
)

internal object EncryptedMessageCodec {
    fun encode(
        authKey: MtProtoAuthKey,
        metadata: MtProtoEncryptedMessageMetadata,
        body: ByteArray,
        entropy: EntropySource,
        x: Int,
    ): ByteArray {
        require(x == CLIENT_X || x == SERVER_X) { "x must be 0 or 8" }
        require(metadata.sessionId != 0L) { "sessionId must not be zero" }
        require(metadata.sequenceNumber >= 0) { "sequenceNumber must not be negative" }
        require(body.size in MIN_BODY_BYTES..MAX_BODY_BYTES && body.size % 4 == 0) {
            "Encrypted message body must be non-empty, bounded, and 4-byte aligned"
        }
        requireMessageId(metadata.messageId, x)
        var padding: ByteArray? = null
        var plaintext: ByteArray? = null
        var authenticatedPayload: ByteArray? = null
        var keyMaterial: ByteArray? = null
        var messageKey: ByteArray? = null
        var aesKey: ByteArray? = null
        var aesIv: ByteArray? = null
        var ciphertext: ByteArray? = null
        return try {
            val paddingSize = MIN_PADDING_BYTES + alignmentPadding(PLAINTEXT_HEADER_BYTES + body.size + MIN_PADDING_BYTES)
            val randomPadding = ByteArray(paddingSize)
            padding = randomPadding
            entropy.nextBytes(randomPadding)
            val plain = ByteBuffer.allocate(PLAINTEXT_HEADER_BYTES + body.size).order(ByteOrder.LITTLE_ENDIAN).apply {
                putLong(metadata.serverSalt)
                putLong(metadata.sessionId)
                putLong(metadata.messageId)
                putInt(metadata.sequenceNumber)
                putInt(body.size)
                put(body)
            }.array()
            plaintext = plain
            val payload = plain + randomPadding
            authenticatedPayload = payload
            val authKeyMaterial = authKey.toByteArray()
            keyMaterial = authKeyMaterial
            val derivedMessageKey = MtProtoKeyDerivation.messageKey(authKeyMaterial, payload, x)
            messageKey = derivedMessageKey
            val aes = MtProtoKeyDerivation.messageAesKeyIv(authKeyMaterial, derivedMessageKey, x)
            try {
                aesKey = aes.key
                aesIv = aes.iv
            } finally {
                aes.close()
            }
            val encrypted = AesIge.encrypt(payload, checkNotNull(aesKey), checkNotNull(aesIv))
            ciphertext = encrypted
            ByteBuffer.allocate(OUTER_HEADER_BYTES + encrypted.size).order(ByteOrder.LITTLE_ENDIAN).apply {
                putLong(authKey.id)
                put(derivedMessageKey)
                put(encrypted)
            }.array()
        } finally {
            keyMaterial?.fill(0)
            messageKey?.fill(0)
            aesKey?.fill(0)
            aesIv?.fill(0)
            ciphertext?.fill(0)
            authenticatedPayload?.fill(0)
            plaintext?.fill(0)
            padding?.fill(0)
        }
    }

    fun decode(
        authKey: MtProtoAuthKey,
        expectedSessionId: Long,
        packet: ByteArray,
        x: Int,
    ): MtProtoEncryptedMessage {
        require(x == CLIENT_X || x == SERVER_X) { "x must be 0 or 8" }
        require(expectedSessionId != 0L) { "expectedSessionId must not be zero" }
        require(packet.size in MIN_PACKET_BYTES..MAX_PACKET_BYTES) { "Invalid encrypted MTProto packet length" }
        val outer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        require(outer.long == authKey.id) { "Encrypted MTProto auth key ID mismatch" }
        val messageKey = ByteArray(MESSAGE_KEY_BYTES).also(outer::get)
        val ciphertext = ByteArray(outer.remaining()).also(outer::get)
        var keyMaterial: ByteArray? = null
        var aesKey: ByteArray? = null
        var aesIv: ByteArray? = null
        var plaintext: ByteArray? = null
        try {
            require(ciphertext.size % BLOCK_BYTES == 0) { "Encrypted MTProto payload must be block-aligned" }
            val authKeyMaterial = authKey.toByteArray()
            keyMaterial = authKeyMaterial
            val aes = MtProtoKeyDerivation.messageAesKeyIv(authKeyMaterial, messageKey, x)
            try {
                aesKey = aes.key
                aesIv = aes.iv
            } finally {
                aes.close()
            }
            val decrypted = AesIge.decrypt(ciphertext, checkNotNull(aesKey), checkNotNull(aesIv))
            plaintext = decrypted
            val actualMessageKey = MtProtoKeyDerivation.messageKey(authKeyMaterial, decrypted, x)
            val validMessageKey = try {
                MessageDigest.isEqual(messageKey, actualMessageKey)
            } finally {
                actualMessageKey.fill(0)
            }
            require(validMessageKey) { "Encrypted MTProto message key mismatch" }
            val buffer = ByteBuffer.wrap(decrypted).order(ByteOrder.LITTLE_ENDIAN)
            val metadata = MtProtoEncryptedMessageMetadata(
                serverSalt = buffer.long,
                sessionId = buffer.long,
                messageId = buffer.long,
                sequenceNumber = buffer.int,
            )
            require(metadata.sessionId == expectedSessionId) { "Encrypted MTProto session ID mismatch" }
            require(metadata.sequenceNumber >= 0) { "Encrypted MTProto sequence number must not be negative" }
            requireMessageId(metadata.messageId, x)
            val bodySize = buffer.int
            require(bodySize in MIN_BODY_BYTES..MAX_BODY_BYTES && bodySize % 4 == 0) {
                "Invalid encrypted MTProto body length"
            }
            require(bodySize <= buffer.remaining()) { "Encrypted MTProto body exceeds plaintext" }
            val paddingSize = buffer.remaining() - bodySize
            require(paddingSize in MIN_PADDING_BYTES..MAX_PADDING_BYTES) { "Invalid encrypted MTProto padding length" }
            val body = ByteArray(bodySize).also(buffer::get)
            return try {
                MtProtoEncryptedMessage(metadata, body)
            } finally {
                body.fill(0)
            }
        } finally {
            keyMaterial?.fill(0)
            messageKey.fill(0)
            aesKey?.fill(0)
            aesIv?.fill(0)
            ciphertext.fill(0)
            plaintext?.fill(0)
        }
    }

    private fun requireMessageId(messageId: Long, x: Int) {
        require(messageId != 0L) { "Encrypted MTProto message ID must not be zero" }
        if (x == CLIENT_X) {
            require(messageId and 3L == 0L) { "Client message ID must be divisible by four" }
        } else {
            require(messageId and 1L == 1L) { "Server message ID must be odd" }
        }
    }

    private fun alignmentPadding(size: Int): Int = (BLOCK_BYTES - size % BLOCK_BYTES) % BLOCK_BYTES

    const val CLIENT_X = 0
    const val SERVER_X = 8
    private const val BLOCK_BYTES = 16
    private const val MESSAGE_KEY_BYTES = 16
    private const val OUTER_HEADER_BYTES = 8 + MESSAGE_KEY_BYTES
    private const val PLAINTEXT_HEADER_BYTES = 8 + 8 + 8 + 4 + 4
    private const val MIN_BODY_BYTES = 4
    private const val MAX_BODY_BYTES = 16 * 1024 * 1024
    private const val MIN_PADDING_BYTES = 12
    private const val MAX_PADDING_BYTES = 1024
    private const val MIN_PACKET_BYTES = OUTER_HEADER_BYTES + BLOCK_BYTES * 3
    private const val MAX_PACKET_BYTES = OUTER_HEADER_BYTES + PLAINTEXT_HEADER_BYTES + MAX_BODY_BYTES + MAX_PADDING_BYTES
}

class MtProtoEncryptedMessage internal constructor(
    val metadata: MtProtoEncryptedMessageMetadata,
    body: ByteArray,
) : AutoCloseable {
    private val lock = Any()
    private val value = body.copyOf()
    private var closed = false

    fun copyBody(): ByteArray = synchronized(lock) {
        check(!closed) { "Decoded encrypted message has been closed" }
        value.copyOf()
    }

    override fun close() = synchronized(lock) {
        if (!closed) value.fill(0)
        closed = true
    }
}
