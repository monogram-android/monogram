package org.monogram.mtproto.codec

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.tl.runtime.TlCodec
import org.monogram.mtproto.tl.runtime.TlDecodeContext
import org.monogram.mtproto.tl.runtime.TlDeferredObject
import org.monogram.mtproto.tl.runtime.TlInt128
import org.monogram.mtproto.tl.runtime.TlInt256
import org.monogram.mtproto.tl.runtime.TlLimitExceededException
import org.monogram.mtproto.tl.runtime.TlLimitKind
import org.monogram.mtproto.tl.runtime.TlLimits
import org.monogram.mtproto.tl.runtime.TlReader
import org.monogram.mtproto.tl.runtime.TlSchemaIdentity

/** Bounded little-endian TL reader. The input is never exposed or retained by a returned value. */
class TlBinaryReader(
    bytes: ByteArray,
    private val absoluteStart: Long = 0,
    limits: TlLimits = TlLimits.DEFAULT,
    private val schema: TlSchemaIdentity = DEFAULT_SCHEMA,
) : TlReader {
    private val source = validatedCopy(bytes, limits, schema, absoluteStart)
    private val end = source.size
    private var position = 0
    private val maxBytes = limits.maxObjectBytes
    private val maxVectorElements = limits.maxVectorElements

    override val absoluteOffset: Long get() = absoluteStart + position
    override val size: Long get() = (end - position).toLong()
    val remaining: Int get() = end - position

    override fun readInt(): Int = readLittleEndian(4).int
    override fun readLong(): Long = readLittleEndian(8).long
    override fun readDouble(): Double = Double.fromBits(readLong())

    override fun readBool(context: TlDecodeContext): Boolean = when (val id = readUInt()) {
        TRUE_ID -> true
        FALSE_ID -> false
        else -> throw malformed("Unknown Bool constructor ${id.toString(16)}", context)
    }

    override fun readBytes(context: TlDecodeContext): TlBytes = TlBytes.copyOf(readByteString(context))
    override fun readString(context: TlDecodeContext): String =
        UTF8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(readByteString(context))).toString()

    override fun readInt128(): TlInt128 = TlInt128.copyOf(readRaw(16))
    override fun readInt256(): TlInt256 = TlInt256.copyOf(readRaw(32))

    override fun readDeferredObject(byteCount: Int, context: TlDecodeContext): TlDeferredObject {
        require(byteCount >= 0) { "byteCount must not be negative" }
        ensureAvailable(byteCount, context)
        return TlDeferredObject.copyOf(readRaw(byteCount), minOf(maxBytes, TlLimits.DEFAULT.maxObjectBytes))
    }

    override fun readRemainingDeferredObject(context: TlDecodeContext): TlDeferredObject =
        TlDeferredObject.copyOf(readRaw(remaining), minOf(maxBytes, TlLimits.DEFAULT.maxObjectBytes))

    override fun <T> readVector(codec: TlCodec<T>, context: TlDecodeContext): List<T> {
        val constructor = readUInt()
        if (constructor != VECTOR_ID) throw malformed("Unknown vector constructor ${constructor.toString(16)}", context)
        return readVectorElements(codec, context)
    }

    private fun <T> readVectorElements(codec: TlCodec<T>, context: TlDecodeContext): List<T> {
        val count = readInt()
        if (count < 0) throw malformed("Negative vector element count $count", context)
        if (count > maxVectorElements) {
            throw TlLimitExceededException(context.schema, TlLimitKind.VECTOR_ELEMENTS, maxVectorElements, count, absoluteOffset)
        }
        return List(count) { codec.read(this, context.nested()) }
    }

    fun <T> decodeFully(codec: TlCodec<T>, context: TlDecodeContext): T {
        val value = codec.read(this, context)
        require(remaining == 0) { "Trailing TL bytes: $remaining at offset $absoluteOffset for ${context.schema}" }
        return value
    }

    private fun readByteString(context: TlDecodeContext): ByteArray {
        val first = readRaw(1)[0].toInt() and 0xff
        if (first == 255) throw malformed("Invalid TL bytes length marker")
        val length = if (first < 254) first else {
            val raw = readRaw(3)
            (raw[0].toInt() and 0xff) or ((raw[1].toInt() and 0xff) shl 8) or ((raw[2].toInt() and 0xff) shl 16)
        }
        if (first >= 254 && length < 254) throw malformed("Non-canonical TL bytes length prefix")
        if (length > maxBytes) throw TlLimitExceededException(context.schema, TlLimitKind.OBJECT_BYTES, maxBytes, length, absoluteOffset)
        val padding = (4 - ((if (first < 254) 1 else 4) + length) % 4) % 4
        val value = readRaw(length)
        val pad = readRaw(padding)
        if (pad.any { it != 0.toByte() }) throw malformed("TL bytes contain non-zero padding")
        return value
    }

    private fun readUInt(): UInt = readInt().toUInt()

    private fun readLittleEndian(bytes: Int): ByteBuffer = ByteBuffer.wrap(readRaw(bytes)).order(ByteOrder.LITTLE_ENDIAN)

    private fun readRaw(count: Int): ByteArray {
        ensureAvailable(count, null)
        return source.copyOfRange(position, position + count).also { position += count }
    }

    private fun ensureAvailable(count: Int, context: TlDecodeContext?) {
        if (count < 0 || count > end - position) {
            throw IllegalArgumentException(
                "Insufficient TL bytes: requested $count, remaining ${end - position} " +
                    "at offset $absoluteOffset for $schema",
            )
        }
    }

    private fun malformed(detail: String, context: TlDecodeContext): IllegalArgumentException =
        IllegalArgumentException("$detail at offset $absoluteOffset for ${context.schema}")

    private fun malformed(detail: String): IllegalArgumentException =
        IllegalArgumentException("$detail at offset $absoluteOffset for $schema")

    private companion object {
        fun validatedCopy(
            bytes: ByteArray,
            limits: TlLimits,
            schema: TlSchemaIdentity,
            absoluteStart: Long,
        ): ByteArray {
            if (bytes.size > limits.maxObjectBytes) {
                throw TlLimitExceededException(
                    schema = schema,
                    limitKind = TlLimitKind.OBJECT_BYTES,
                    configuredMaximum = limits.maxObjectBytes,
                    observedValue = bytes.size,
                    absoluteOffset = absoluteStart,
                )
            }
            return bytes.copyOf()
        }

        val UTF8 = Charsets.UTF_8
        const val TRUE_ID = 0x997275b5u
        const val FALSE_ID = 0xbc799737u
        const val VECTOR_ID = 0x1cb5c415u
        val DEFAULT_SCHEMA = org.monogram.mtproto.tl.runtime.TlSchemaIdentity(
            org.monogram.mtproto.tl.runtime.TlSchemaKind.TRANSPORT,
            null,
        )
    }
}
