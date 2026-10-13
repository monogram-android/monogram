package org.monogram.network.http

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal suspend fun preloadMp4Metadata(
    fileSize: Long,
    read: suspend (Long, Int) -> ByteArray?,
    cover: suspend (Long, Long) -> Boolean,
) {
    if (fileSize < 8) return
    var offset = 0L
    repeat(128) {
        currentCoroutineContext().ensureActive()
        if (offset > fileSize - 8) return
        val header = read(offset, minOf(16L, fileSize - offset).toInt()) ?: return
        if (header.size < 8) return
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val shortSize = buffer.int.toLong() and 0xffffffffL
        val type = buffer.int
        val headerSize = if (shortSize == 1L) 16L else 8L
        val size = when (shortSize) {
            0L -> fileSize - offset
            1L -> if (header.size >= 16) buffer.long else return
            else -> shortSize
        }
        if (size < headerSize || size > fileSize - offset) return
        if (offset == 0L && type != 0x66747970) return
        if (type == 0x6d6f6f76) {
            if (size <= 2L * 1024 * 1024) cover(offset, size)
            return
        }
        offset += size
    }
}
