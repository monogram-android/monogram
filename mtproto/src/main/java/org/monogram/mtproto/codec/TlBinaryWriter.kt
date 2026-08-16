package org.monogram.mtproto.codec

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.tl.runtime.TlCodec
import org.monogram.mtproto.tl.runtime.TlDeferredObject
import org.monogram.mtproto.tl.runtime.TlInt128
import org.monogram.mtproto.tl.runtime.TlInt256
import org.monogram.mtproto.tl.runtime.TlLimits
import org.monogram.mtproto.tl.runtime.TlWriter

/** Canonical growable TL writer with a hard object-size limit. */
class TlBinaryWriter(private val limits: TlLimits = TlLimits.DEFAULT) : TlWriter {
    private val output = ByteArrayOutputStream()

    override val absoluteOffset: Long get() = output.size().toLong()
    override val size: Long get() = output.size().toLong()

    override fun writeInt(value: Int) = writeLittleEndian(4) { putInt(value) }
    override fun writeLong(value: Long) = writeLittleEndian(8) { putLong(value) }
    override fun writeDouble(value: Double) = writeLong(value.toBits())

    override fun writeBool(value: Boolean) = writeInt(if (value) TRUE_ID.toInt() else FALSE_ID.toInt())
    override fun writeBytes(value: TlBytes) = writeByteString(value.toByteArray())
    override fun writeString(value: String) = writeByteString(value.toByteArray(Charsets.UTF_8))
    override fun writeInt128(value: TlInt128) = writeRaw(value.toByteArray())
    override fun writeInt256(value: TlInt256) = writeRaw(value.toByteArray())
    override fun writeDeferredObject(value: TlDeferredObject) = writeRaw(value.toByteArray())

    override fun <T> writeVector(values: List<T>, codec: TlCodec<T>) {
        require(values.size <= limits.maxVectorElements) { "Vector exceeds configured element limit" }
        writeInt(VECTOR_ID.toInt())
        writeInt(values.size)
        values.forEach { codec.write(this, it) }
    }

    fun toByteArray(): ByteArray = output.toByteArray()

    private fun writeByteString(value: ByteArray) {
        require(value.size <= MAX_LONG_BYTES) { "TL bytes exceed the 24-bit wire length" }
        val short = value.size < 254
        val prefix = if (short) 1 else 4
        val padding = (4 - (prefix + value.size) % 4) % 4
        val encodedSize = prefix + value.size + padding
        require(output.size() <= limits.maxObjectBytes - encodedSize) { "TL bytes exceed configured object limit" }
        if (short) writeRaw(byteArrayOf(value.size.toByte())) else {
            writeRaw(byteArrayOf(254.toByte(), (value.size and 0xff).toByte(), (value.size ushr 8 and 0xff).toByte(), (value.size ushr 16 and 0xff).toByte()))
        }
        writeRaw(value)
        repeat(padding) { writeRaw(byteArrayOf(0)) }
    }

    private fun writeLittleEndian(bytes: Int, fill: ByteBuffer.() -> Unit) {
        val buffer = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN).apply(fill)
        writeRaw(buffer.array())
    }

    private fun writeRaw(value: ByteArray) {
        require(output.size() <= limits.maxObjectBytes - value.size) { "TL output exceeds configured object limit" }
        output.write(value)
    }

    private companion object {
        const val TRUE_ID = 0x997275b5u
        const val FALSE_ID = 0xbc799737u
        const val VECTOR_ID = 0x1cb5c415u
        const val MAX_LONG_BYTES = 0x00ffffff
    }
}
