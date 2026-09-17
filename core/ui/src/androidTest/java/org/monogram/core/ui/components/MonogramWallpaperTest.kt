package org.monogram.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The pre-rasterized wallpaper must repeat on the 96.dp grid without seams or stray tiles. */
class MonogramWallpaperTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun wallpaperRepeatsOnThePatternGrid() {
        rule.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 320.dp).testTag("wall")) {
                    MonogramWallpaper(Modifier.fillMaxSize())
                }
            }
        }
        val pixels = rule.onNodeWithTag("wall").captureToImage().toPixelMap()
        val stepX = (rule.density.density * 96f).roundToInt()
        val stepY = stepX * 2

        var sampled = 0
        var mismatched = 0
        var worst = 0f
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val here = pixels[x, y]
                if (x + stepX < pixels.width) {
                    val delta = channelDelta(here, pixels[x + stepX, y])
                    worst = maxOf(worst, delta)
                    if (delta > 2f) mismatched++
                    sampled++
                }
                if (y + stepY < pixels.height) {
                    val delta = channelDelta(here, pixels[x, y + stepY])
                    worst = maxOf(worst, delta)
                    if (delta > 2f) mismatched++
                    sampled++
                }
            }
        }
        assertTrue(
            "wallpaper breaks its $stepX x $stepY grid on $mismatched of $sampled pixels (worst $worst)",
            mismatched <= sampled / 1000,
        )
    }
}

private fun channelDelta(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Float =
    maxOf(
        abs(a.red - b.red),
        abs(a.green - b.green),
        abs(a.blue - b.blue),
        abs(a.alpha - b.alpha),
    ) * 255f
