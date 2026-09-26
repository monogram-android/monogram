package org.monogram.feature.settings

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.coroutines.runBlocking

class WallpaperPatternTest {
    @Test fun acceptsExactLimit() = runTest {
        val bytes = ByteArray(100) { it.toByte() }
        assertArrayEquals(bytes, readWallpaperPattern(bytes.inputStream(), 100))
    }

    @Test fun rejectsDecompressedPatternAboveLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { readWallpaperPattern(ByteArray(101).inputStream(), 100) }
        }
    }
}
