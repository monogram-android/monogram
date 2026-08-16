package org.monogram.mtproto.codec

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
        GZIPInputStream(ByteArrayInputStream(compressed)).use { gzip ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = gzip.read(buffer)
                if (read < 0) break
                val observed = output.size() + read
                if (observed > context.limits.maxDecompressedBytes) {
                    throw limit(context, TlLimitKind.DECOMPRESSED_BYTES, context.limits.maxDecompressedBytes, observed)
                }
                if (compressed.isNotEmpty() && observed.toLong() > compressed.size.toLong() * context.limits.maxGzipRatio) {
                    throw limit(context, TlLimitKind.GZIP_RATIO, context.limits.maxGzipRatio, ratio(observed, compressed.size))
                }
                output.write(buffer, 0, read)
            }
        }
        return TlDeferredObject.copyOf(output.toByteArray(), context.limits.maxDecompressedBytes)
    }

    private fun limit(
        context: TlDecodeContext,
        kind: TlLimitKind,
        configured: Int,
        observed: Int,
    ) = TlLimitExceededException(context.schema, kind, configured, observed, null)

    private fun ratio(outputBytes: Int, inputBytes: Int): Int =
        ((outputBytes.toLong() + inputBytes - 1) / inputBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
