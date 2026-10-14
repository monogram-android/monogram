package org.monogram.core.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class MediaCacheLimitTest {
    @Test
    fun unknownOrMissingChoiceIsOneGigabyte() {
        assertEquals(MediaCacheLimit.GB_1, MediaCacheLimit.DEFAULT)
        assertEquals(1L shl 30, MediaCacheLimit.GB_1.bytes)
        assertEquals(MediaCacheLimit.GB_1, MediaCacheLimit.fromStored(null))
        assertEquals(MediaCacheLimit.GB_1, MediaCacheLimit.fromStored("nope"))
        assertEquals(null, MediaCacheLimit.UNLIMITED.bytes)
    }

    @Test
    fun storedChoiceSurvivesANewRead() {
        val directory = File.createTempFile("media-limit", "").apply {
            delete()
            mkdirs()
        }
        try {
            assertEquals(MediaCacheLimit.GB_1, MediaCacheLimitStore.read(directory))
            MediaCacheLimit.entries.forEach { limit ->
                MediaCacheLimitStore.write(directory, limit)
                assertEquals(limit, MediaCacheLimitStore.read(directory))
                assertEquals(limit, MediaCacheLimitStore.current.value)
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
