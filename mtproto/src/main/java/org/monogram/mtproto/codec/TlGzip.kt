package org.monogram.mtproto.codec

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.tl.runtime.TlDecodeContext
import org.monogram.mtproto.tl.runtime.TlDeferredObject
import org.monogram.mtproto.tl.runtime.TlLimitExceededException
import org.monogram.mtproto.tl.runtime.TlLimitKind

object TlGzip {
    fun decompress(packed: TlBytes, context: TlDecodeContext): TlDeferredObject {
        val compressed = packed.toByteArray()
        val output = ByteArrayOutputStream()
        val compressedInput = CountingInputStream(ByteArrayInputStream(compressed))
        var consumed = 0
        val outputLimit = minOf(context.limits.maxDecompressedBytes, context.limits.maxObjectBytes)
        val outputLimitKind = if (context.limits.maxObjectBytes < context.limits.maxDecompressedBytes) {
            TlLimitKind.OBJECT_BYTES
        } else {
            TlLimitKind.DECOMPRESSED_BYTES
        }
        try {
            CountingGzipInputStream(compressedInput).use { gzip ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = gzip.read(buffer)
                    if (read < 0) break
                    val observed = output.size() + read
                    if (observed > outputLimit) {
                        throw limit(context, outputLimitKind, outputLimit, observed)
                    }
                    output.write(buffer, 0, read)
                }
                consumed = gzip.consumedByInflater
            }
        } catch (failure: IOException) {
            throw IllegalArgumentException("Malformed gzip payload for ${context.schema}", failure)
        }
        if (consumed > 0 && output.size().toLong() > consumed.toLong() * context.limits.maxGzipRatio) {
            throw limit(context, TlLimitKind.GZIP_RATIO, context.limits.maxGzipRatio, ratio(output.size(), consumed))
        }
        return TlDeferredObject.copyOf(output.toByteArray(), outputLimit)
    }

    private fun limit(
        context: TlDecodeContext,
        kind: TlLimitKind,
        configured: Int,
        observed: Int,
    ) = TlLimitExceededException(context.schema, kind, configured, observed, null)

    private fun ratio(outputBytes: Int, inputBytes: Int): Int =
        ((outputBytes.toLong() + inputBytes - 1) / inputBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count: Int = 0
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) count += it }
    }

    private class CountingGzipInputStream(
        private val counted: CountingInputStream,
    ) : GZIPInputStream(counted) {
        val consumedByInflater: Int
            get() = counted.count - inf.remaining
    }
}
