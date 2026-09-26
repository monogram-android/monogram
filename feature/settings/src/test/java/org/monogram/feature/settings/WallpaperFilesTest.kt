package org.monogram.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class WallpaperFilesTest(
    private val width: Int,
    private val height: Int,
    private val expected: Int,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}x{1} -> {2}")
        fun data(): Collection<Array<Any>> = listOf(
            arrayOf(1080, 2400, 1),
            arrayOf(8000, 6000, 4),
            arrayOf(6000, 8000, 4),
        )
    }

    @Test
    fun calculatesSampleSize() {
        assertEquals(expected, wallpaperSampleSize(width, height))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectUndecodableImage() {
        wallpaperSampleSize(-1, -1)
    }
}
