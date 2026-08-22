package org.monogram.mtproto.transport

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal sealed interface IntermediateFrame {
    data class Message(val payload: ByteArray) : IntermediateFrame
    data class QuickAck(val token: UInt) : IntermediateFrame
}

internal object IntermediateTransportFraming {
    val preamble: ByteArray get() = byteArrayOf(0xee.toByte(), 0xee.toByte(), 0xee.toByte(), 0xee.toByte())

    fun encode(payload: ByteArray, includePreamble: Boolean = false): ByteArray {
        require(payload.isNotEmpty()) { "Intermediate payload must not be empty" }
        require(payload.size % WORD_BYTES == 0) { "Intermediate payload must be 4-byte aligned" }
        require(payload.size < MAX_PAYLOAD_BYTES) { "Intermediate payload exceeds the 24-bit limit" }
        val prefix = if (includePreamble) PREAMBLE_BYTES else 0
        return ByteArray(prefix + HEADER_BYTES + payload.size).also { output ->
            if (includePreamble) preamble.copyInto(output)
            writeLittleEndianInt(output, prefix, payload.size)
            payload.copyInto(output, prefix + HEADER_BYTES)
        }
    }

    fun expectedFrameBytes(header: ByteArray): Int {
        require(header.size == HEADER_BYTES) { "Intermediate header must contain 4 bytes" }
        val value = readLittleEndianUInt(header)
        if (value and QUICK_ACK_MASK != 0u) return HEADER_BYTES
        val payloadBytes = value.toLong()
        require(payloadBytes in WORD_BYTES.toLong() until MAX_PAYLOAD_BYTES.toLong()) {
            "Invalid intermediate payload length"
        }
        require(payloadBytes % WORD_BYTES == 0L) { "Intermediate payload must be 4-byte aligned" }
        return HEADER_BYTES + payloadBytes.toInt()
    }

    fun decode(frame: ByteArray): IntermediateFrame {
        require(frame.size >= HEADER_BYTES) { "Truncated intermediate frame" }
        val header = frame.copyOfRange(0, HEADER_BYTES)
        val expected = try {
            expectedFrameBytes(header)
        } finally {
            header.fill(0)
        }
        require(frame.size == expected) { "Intermediate frame length mismatch" }
        val value = readLittleEndianUInt(frame)
        return if (value and QUICK_ACK_MASK != 0u) {
            IntermediateFrame.QuickAck(value)
        } else {
            IntermediateFrame.Message(frame.copyOfRange(HEADER_BYTES, frame.size))
        }
    }

    private fun readLittleEndianUInt(bytes: ByteArray): UInt =
        ByteBuffer.wrap(bytes, 0, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).int.toUInt()

    private fun writeLittleEndianInt(destination: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(destination, offset, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
    }

    private const val PREAMBLE_BYTES = 4
    private const val HEADER_BYTES = 4
    private const val WORD_BYTES = 4
    private const val MAX_PAYLOAD_BYTES = 1 shl 24
    private const val QUICK_ACK_MASK = 0x80000000u
}
