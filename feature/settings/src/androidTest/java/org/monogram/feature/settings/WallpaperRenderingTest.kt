package org.monogram.feature.settings

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.monogram.core.models.Wallpaper

@RunWith(AndroidJUnit4::class)
class WallpaperRenderingTest {
    private val background = 0x44aa22
    private fun wallpaper(intensity: Int) = Wallpaper(1, 2, "fixture", true, false,
        "application/x-tgwallpattern", 3, listOf(background), intensity, 0, false, false)

    @Test fun positivePatternOverlaysFill() = checkPattern(100, Color.BLACK, background or Color.BLACK)
    @Test fun negativePatternPreservesFillInsideMask() = checkPattern(-100, background or Color.BLACK, Color.BLACK)

    private fun checkPattern(intensity: Int, expectedCenter: Int, expectedCorner: Int) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "wallpaper-render-test").apply { mkdirs() }
        val document = File(directory, "pattern.tgv")
        val destination = File(directory, "render.jpg")
        try {
            GZIPOutputStream(document.outputStream()).use {
                it.write("""<svg xmlns="http://www.w3.org/2000/svg" width="1080" height="1920" viewBox="0 0 1080 1920"><circle cx="540" cy="960" r="240" fill="black"/></svg>""".toByteArray())
            }
            renderWallpaper(wallpaper(intensity), document, destination)
            val bitmap = requireNotNull(BitmapFactory.decodeFile(destination.path))
            try {
                assertEquals(1080, bitmap.width)
                assertEquals(1920, bitmap.height)
                assertColor(expectedCenter, bitmap.getPixel(540, 960))
                assertColor(expectedCorner, bitmap.getPixel(10, 10))
            } finally { bitmap.recycle() }
        } finally { directory.deleteRecursively() }
    }

    @Test fun malformedPatternLeavesNoPartialWallpaper() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "wallpaper-invalid-test").apply { mkdirs() }
        val document = File(directory, "invalid.tgv")
        val destination = File(directory, "render.jpg")
        try {
            GZIPOutputStream(document.outputStream()).use { it.write("not an svg".toByteArray()) }
            val failure = runCatching { renderWallpaper(wallpaper(50), document, destination) }.exceptionOrNull()
            assertNotNull(failure)
            assertFalse(destination.exists())
            assertFalse(File(destination.path + ".tmp").exists())
        } finally { directory.deleteRecursively() }
    }

    private fun assertColor(expected: Int, actual: Int) {
        assertTrue(kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= 3)
        assertTrue(kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= 3)
        assertTrue(kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= 3)
    }
}
