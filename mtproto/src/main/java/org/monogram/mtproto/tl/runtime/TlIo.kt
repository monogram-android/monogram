package org.monogram.mtproto.tl.runtime

interface TlReader {
    val absoluteOffset: Long
    val size: Long

    fun readInt(): Int

    fun readLong(): Long

    fun readDouble(): Double

    fun readBool(context: TlDecodeContext): Boolean

    fun readBytes(context: TlDecodeContext): TlBytes

    fun readString(context: TlDecodeContext): String

    fun readInt128(): TlInt128

    fun readInt256(): TlInt256

    fun readDeferredObject(byteCount: Int, context: TlDecodeContext): TlDeferredObject

    fun readRemainingDeferredObject(context: TlDecodeContext): TlDeferredObject

    fun <T> readVector(codec: TlCodec<T>, context: TlDecodeContext): List<T>
}

internal fun <T> readBareVector(
    reader: TlReader,
    codec: TlCodec<T>,
    context: TlDecodeContext,
): List<T> {
    val count = reader.readInt()
    require(count >= 0) { "Negative vector element count $count" }
    require(count <= TlLimits.DEFAULT.maxVectorElements) { "Vector exceeds configured element limit" }
    return List(count) { codec.read(reader, context.nested()) }
}

internal fun <T> writeBareVector(
    writer: TlWriter,
    values: List<T>,
    codec: TlCodec<T>,
) {
    require(values.size <= TlLimits.DEFAULT.maxVectorElements) { "Vector exceeds configured element limit" }
    writer.writeInt(values.size)
    values.forEach { codec.write(writer, it) }
}

interface TlWriter {
    val absoluteOffset: Long
    val size: Long

    fun writeInt(value: Int)

    fun writeLong(value: Long)

    fun writeDouble(value: Double)

    fun writeBool(value: Boolean)

    fun writeBytes(value: TlBytes)

    fun writeString(value: String)

    fun writeInt128(value: TlInt128)

    fun writeInt256(value: TlInt256)

    fun writeDeferredObject(value: TlDeferredObject)

    fun <T> writeVector(values: List<T>, codec: TlCodec<T>)
}
