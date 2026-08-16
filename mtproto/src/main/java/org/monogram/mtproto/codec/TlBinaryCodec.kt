package org.monogram.mtproto.codec

import org.monogram.mtproto.tl.runtime.TlCodec
import org.monogram.mtproto.tl.runtime.TlConstructorRegistry
import org.monogram.mtproto.tl.runtime.TlDecodeContext
import org.monogram.mtproto.tl.runtime.TlLimits
import org.monogram.mtproto.tl.runtime.TlObject
import org.monogram.mtproto.tl.runtime.TlSchemaMismatchException

object TlBinaryCodec {
    fun <T> encode(codec: TlCodec<T>, value: T, limits: TlLimits = TlLimits.DEFAULT): ByteArray =
        TlBinaryWriter(limits).also { codec.write(it, value) }.toByteArray()

    fun <T> decode(codec: TlCodec<T>, bytes: ByteArray, context: TlDecodeContext): T =
        TlBinaryReader(bytes, limits = context.limits, schema = context.schema).decodeFully(codec, context)

    fun decodeObject(
        registry: TlConstructorRegistry,
        bytes: ByteArray,
        context: TlDecodeContext,
    ): TlObject {
        val reader = TlBinaryReader(bytes, limits = context.limits, schema = context.schema)
        if (registry.schema != context.schema) {
            throw TlSchemaMismatchException(
                expectedSchema = context.schema,
                actualSchema = registry.schema,
                absoluteOffset = reader.absoluteOffset,
            )
        }
        val id = reader.readInt().toUInt()
        val value = registry.decode(id, reader, context)
        require(reader.remaining == 0) { "Trailing TL bytes: ${reader.remaining}" }
        return value
    }
}
