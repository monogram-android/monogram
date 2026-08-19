package org.monogram.mtproto.transport

import org.monogram.mtproto.crypto.EntropySource
import org.monogram.mtproto.crypto.SecureEntropySource
import org.monogram.mtproto.handshake.MtProtoAuthKey

internal data class EncodedEncryptedMessage(
    val metadata: MtProtoEncryptedMessageMetadata,
    val packet: ByteArray,
)

internal data class DecodedEncryptedMessage(
    val message: MtProtoEncryptedMessage,
    val duplicate: Boolean,
)

/** Owns [authKey] after successful construction; closing the session destroys its key material. */
class MtProtoEncryptedSession internal constructor(
    private val authKey: MtProtoAuthKey,
    private val entropy: EntropySource,
    currentTimeMillis: () -> Long,
) : AutoCloseable {
    constructor(authKey: MtProtoAuthKey) : this(authKey, SecureEntropySource, System::currentTimeMillis)

    private val lock = Any()
    private val currentTimeMillis = currentTimeMillis
    private var serverTimeOffsetMillis = authKey.createdAt * 1_000L - currentTimeMillis()
    private var serverTimeCalibrated = false
    private val messageIds = ClientMessageIdGenerator { currentTimeMillis() + serverTimeOffsetMillis }
    private val inboundMessageIds = LinkedHashSet<Long>()
    val sessionId: Long = generateSessionId(entropy)
    private var salt = authKey.serverSalt
    private var contentRelatedMessages = 0
    private var closed = false

    val serverSalt: Long get() = synchronized(lock) { salt }

    fun encode(body: ByteArray, contentRelated: Boolean): ByteArray =
        encodeTracked(body, contentRelated).packet

    internal fun encodeTracked(body: ByteArray, contentRelated: Boolean): EncodedEncryptedMessage = synchronized(lock) {
        check(!closed) { "MTProto encrypted session is closed" }
        val sequenceNumber = if (contentRelated) {
            check(contentRelatedMessages < Int.MAX_VALUE / 2) { "MTProto sequence number exhausted" }
            (contentRelatedMessages * 2) + 1
        } else {
            contentRelatedMessages * 2
        }
        val metadata = MtProtoEncryptedMessageMetadata(salt, sessionId, messageIds.next(), sequenceNumber)
        val packet = EncryptedMessageCodec.encode(
            authKey,
            metadata,
            body,
            entropy,
            EncryptedMessageCodec.CLIENT_X,
        )
        if (contentRelated) contentRelatedMessages++
        EncodedEncryptedMessage(metadata, packet)
    }

    fun decode(packet: ByteArray): MtProtoEncryptedMessage = synchronized(lock) {
        val decoded = decodeTracked(packet)
        try {
            require(!decoded.duplicate) { "Encrypted MTProto message ID was already received" }
            decoded.message
        } catch (failure: Throwable) {
            decoded.message.close()
            throw failure
        }
    }

    internal fun decodeTracked(packet: ByteArray): DecodedEncryptedMessage = synchronized(lock) {
        check(!closed) { "MTProto encrypted session is closed" }
        val message = EncryptedMessageCodec.decode(authKey, sessionId, packet, EncryptedMessageCodec.SERVER_X)
        try {
            DecodedEncryptedMessage(message, duplicate = !admitInbound(message.metadata))
        } catch (failure: Throwable) {
            message.close()
            throw failure
        }
    }

    internal fun admitNested(metadata: MtProtoEncryptedMessageMetadata): Boolean = synchronized(lock) {
        check(!closed) { "MTProto encrypted session is closed" }
        admitInbound(metadata)
    }

    fun updateServerSalt(serverSalt: Long) = synchronized(lock) {
        check(!closed) { "MTProto encrypted session is closed" }
        salt = serverSalt
    }

    private fun isFresh(messageId: Long): Boolean {
        val nowSeconds = (currentTimeMillis() + serverTimeOffsetMillis) / 1_000L
        val messageSeconds = messageId ushr 32
        return messageSeconds > nowSeconds - MAX_INBOUND_AGE_SECONDS &&
            messageSeconds < nowSeconds + MAX_INBOUND_FUTURE_SECONDS
    }

    private fun admitInbound(metadata: MtProtoEncryptedMessageMetadata): Boolean {
        require(metadata.messageId != 0L && metadata.messageId and 1L == 1L) {
            "Server message ID must be non-zero and odd"
        }
        require(metadata.sequenceNumber >= 0) { "Encrypted MTProto sequence number must not be negative" }
        if (metadata.messageId in inboundMessageIds) return false
        calibrateServerTime(metadata.messageId)
        require(isFresh(metadata.messageId)) { "Encrypted MTProto message ID is outside the accepted time window" }
        require(inboundMessageIds.size < MAX_TRACKED_INBOUND_IDS) {
            "Encrypted MTProto replay window capacity exceeded"
        }
        inboundMessageIds += metadata.messageId
        return true
    }

    /** TDLib calibrates from the first authenticated server message before enforcing its replay window. */
    private fun calibrateServerTime(messageId: Long) {
        val observedOffsetMillis = (messageId ushr 32) * 1_000L - currentTimeMillis()
        if (!serverTimeCalibrated || observedOffsetMillis > serverTimeOffsetMillis) {
            serverTimeOffsetMillis = observedOffsetMillis
            serverTimeCalibrated = true
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) authKey.close()
        inboundMessageIds.clear()
        closed = true
    }

    private companion object {
        const val MAX_TRACKED_INBOUND_IDS = 65_536
        const val MAX_INBOUND_AGE_SECONDS = 300L
        const val MAX_INBOUND_FUTURE_SECONDS = 30L

        fun generateSessionId(entropy: EntropySource): Long {
            repeat(32) {
                val bytes = ByteArray(Long.SIZE_BYTES)
                try {
                    entropy.nextBytes(bytes)
                    var value = 0L
                    for (index in bytes.indices) value = value or ((bytes[index].toLong() and 0xffL) shl (index * 8))
                    if (value != 0L) return value
                } finally {
                    bytes.fill(0)
                }
            }
            throw IllegalStateException("Unable to generate non-zero MTProto session ID")
        }
    }
}
