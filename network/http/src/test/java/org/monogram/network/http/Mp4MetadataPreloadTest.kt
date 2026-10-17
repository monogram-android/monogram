package org.monogram.network.http

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class Mp4MetadataPreloadTest {
    @Test
    fun findsMetadataInThePrefix() = runBlocking {
        var covered: Pair<Long, Long>? = null
        preloadMp4Metadata(80, read = { offset, _ ->
            when (offset) {
                0L -> atom(16, 0x66747970); 16L -> atom(64, 0x6d6f6f76); else -> null
            }
        }, cover = { offset, size -> covered = offset to size; true })
        assertEquals(16L to 64L, covered)
    }

    @Test
    fun rejectsOversizedMetadataAndInvalidExtendedLengths() = runBlocking {
        var covers = 0
        for (size in listOf(-1L, Long.MAX_VALUE, 8L, 3L * 1024 * 1024)) {
            preloadMp4Metadata(4L * 1024 * 1024, read = { offset, _ ->
                if (offset == 0L) atom(16, 0x66747970)
                else ByteBuffer.allocate(16).putInt(1).putInt(0x6d6f6f76).putLong(size).array()
            }, cover = { _, _ -> covers++; true })
        }
        assertEquals(0, covers)
    }

    @Test
    fun truncatedExtendedHeaderFallsBack() = runBlocking {
        var covers = 0
        preloadMp4Metadata(64, read = { offset, _ ->
            if (offset == 0L) atom(16, 0x66747970)
            else ByteBuffer.allocate(8).putInt(1).putInt(0x6d6f6f76).array()
        }, cover = { _, _ -> covers++; true })
        assertEquals(0, covers)
    }

    @Test
    fun findsMetadataAfterMediaWithoutReadingMediaPayload() = runBlocking {
        val headers = mapOf(
            0L to atom(16, 0x66747970),
            16L to atom(4 * 1024 * 1024, 0x6d646174),
            (16L + 4 * 1024 * 1024) to atom(64, 0x6d6f6f76),
        )
        val reads = mutableListOf<Long>()
        var covered: Pair<Long, Long>? = null
        preloadMp4Metadata(16L + 4 * 1024 * 1024 + 64, read = { offset, count ->
            reads += offset
            headers[offset]?.copyOf(count)
        }, cover = { offset, size -> covered = offset to size; true })
        assertEquals(headers.keys.toList(), reads)
        assertEquals((16L + 4 * 1024 * 1024) to 64L, covered)
    }

    @Test
    fun rejectsAtomOutsideTheFile() = runBlocking {
        var covers = 0
        preloadMp4Metadata(
            32, read = { _, _ -> atom(128, 0x66747970) },
            cover = { _, _ -> covers++; true })
        assertEquals(0, covers)
    }

    private fun atom(size: Int, type: Int): ByteArray =
        ByteBuffer.allocate(16).putInt(size).putInt(type).array()
}
