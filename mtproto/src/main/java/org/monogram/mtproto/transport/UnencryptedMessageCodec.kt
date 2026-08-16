package org.monogram.mtproto.transport

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import org.monogram.mtproto.codec.TlBinaryCodec
import org.monogram.mtproto.codec.TlBinaryWriter
import org.monogram.mtproto.tl.generated.transport.registry.TransportConstructorRegistry
import org.monogram.mtproto.tl.runtime.TlDecodeContext
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.tl.runtime.TlObject

internal data class UnencryptedMessage(
    val messageId: Long,
    val body: ByteArray,
)

internal class ClientMessageIdGenerator(
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private val last = AtomicLong(0)

    fun next(): Long {
        val nowMillis = currentTimeMillis()
        require(nowMillis >= 0) { "Clock must not precede the Unix epoch" }
        val seconds = nowMillis / MILLIS_PER_SECOND
        val fractionMillis = nowMillis % MILLIS_PER_SECOND
        val timeBased = (seconds shl 32) or ((fractionMillis shl 32) / MILLIS_PER_SECOND)
        val aligned = timeBased and CLIENT_ALIGNMENT_MASK
        while (true) {
            val previous = last.get()
            val candidate = maxOf(aligned, previous + CLIENT_INCREMENT)
            if (last.compareAndSet(previous, candidate)) return candidate
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val CLIENT_INCREMENT = 4L
        const val CLIENT_ALIGNMENT_MASK = -4L
    }
}

internal object UnencryptedMessageCodec {
    fun <R : TlObject> encodeMethod(method: TlMethod<R>, messageId: Long): ByteArray {
        require(messageId > 0 && messageId % 4 == 0L) { "Client message ID must be positive and divisible by four" }
        val writer = TlBinaryWriter()
        TransportConstructorRegistry.encodeMethod(writer, method)
        val body = writer.toByteArray()
        return try {
            encode(messageId, body)
        } finally {
            body.fill(0)
        }
    }

    fun encode(messageId: Long, body: ByteArray): ByteArray {
        require(messageId > 0 && messageId % 4 == 0L) { "Client message ID must be positive and divisible by four" }
        require(body.isNotEmpty() && body.size % 4 == 0) { "Unencrypted body must be non-empty and 4-byte aligned" }
        require(body.size <= MAX_BODY_BYTES) { "Unencrypted body exceeds the limit" }
        return ByteBuffer.allocate(HEADER_BYTES + body.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(0L)
            putLong(messageId)
            putInt(body.size)
            put(body)
        }.array()
    }

    fun decode(bytes: ByteArray): UnencryptedMessage {
        require(bytes.size >= HEADER_BYTES) { "Truncated unencrypted message" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.long == 0L) { "Unencrypted auth_key_id must be zero" }
        val messageId = buffer.long
        require(messageId > 0 && messageId and 1L == 1L) { "Server message ID must be positive and odd" }
        val bodySize = buffer.int
        require(bodySize in 4..MAX_BODY_BYTES && bodySize % 4 == 0) { "Invalid unencrypted body length" }
        require(bodySize == buffer.remaining()) { "Unencrypted body length mismatch" }
        return UnencryptedMessage(messageId, ByteArray(bodySize).also(buffer::get))
    }

    fun <R : TlObject> decodeResult(
        method: TlMethod<R>,
        bytes: ByteArray,
        context: TlDecodeContext,
    ): R {
        val message = decode(bytes)
        return try {
            TlBinaryCodec.decode(method.resultCodec, message.body, context)
        } finally {
            message.body.fill(0)
        }
    }

    private const val HEADER_BYTES = 20
    private const val MAX_BODY_BYTES = 16 * 1024 * 1024
}
