package org.monogram.core.ui.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.monogram.core.ui.theme.MonogramTheme
import java.io.File

class MediaViewerCaptionStabilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun photo(name: String, background: Int, accent: Int): File {
        val target = File(compose.activity.cacheDir, name)
        if (target.exists()) return target
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(background)
            drawRect(80f, 80f, 700f, 820f, android.graphics.Paint().apply { color = accent })
        }
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return target
    }

    private val caption = "A caption long enough to fill the collapsed caption row"

    private fun album(): List<MediaViewerItem> = listOf(
        MediaViewerItem(
            id = "c1",
            kind = MediaViewerKind.PHOTO,
            source = MediaSource.Local(photo("stab-a.png", Color.rgb(230, 232, 240), Color.rgb(90, 140, 220))),
            preview = photo("stab-a.png", Color.rgb(230, 232, 240), Color.rgb(90, 140, 220)),
            caption = caption,
            senderName = "Anna",
            dateLabel = "yesterday",
        ),
        MediaViewerItem(
            id = "c2",
            kind = MediaViewerKind.PHOTO,
            source = MediaSource.Local(photo("stab-b.png", Color.rgb(18, 20, 26), Color.rgb(200, 90, 140))),
            preview = photo("stab-b.png", Color.rgb(18, 20, 26), Color.rgb(200, 90, 140)),
            senderName = "Anna",
            dateLabel = "yesterday",
        ),
    )

    @Test
    fun theBottomClusterDoesNotMoveWhenTheCaptionComesAndGoes() {
        var index by mutableStateOf(0)
        var rebuild by mutableIntStateOf(0)
        compose.setContent {
            MonogramTheme {
                val context = LocalContext.current
                Box(Modifier.fillMaxSize()) { Text("chat") }
                // Rebuilt on every recomposition, exactly like the chat viewer does.
                val items = album().map { it }
                rebuild
                MediaViewerTheme {
                    MediaViewerShell(
                        album = rememberAlbumState(items, startIndex = 0),
                        onDismiss = {},
                        chatKey = "stability",
                        onIndexChange = { index = it },
                    )
                }
            }
        }
        compose.waitForIdle()

        val withCaption = settledClusterTop()
        compose.onRoot().performTouchInput { swipe(centerRight, centerLeft, 220) }
        compose.waitUntil(8_000) { index == 1 }
        compose.waitForIdle()
        val withoutCaption = settledClusterTop()

        val delta = kotlin.math.abs(withoutCaption - withCaption)
        assertTrue(
            "bottom chrome moved ${delta}px when the caption block came and went " +
                "(with caption=$withCaption, without=$withoutCaption)",
            delta <= 12,
        )

        compose.runOnIdle { rebuild++ }
        compose.waitForIdle()
        assertTrue(
            "bottom chrome moved after a rebuild",
            kotlin.math.abs(settledClusterTop() - withoutCaption) <= 12,
        )
    }

    private fun settledClusterTop(): Int {
        var previous = -1
        repeat(25) {
            val value = bottomClusterTop()
            if (value > 0 && value == previous) return value
            previous = value
            Thread.sleep(60)
        }
        assertTrue("the chrome surface was never found by the probe (last=$previous)", previous > 0)
        return previous
    }

    private fun bottomClusterTop(): Int {
        val frame = instrumentation.uiAutomation.takeScreenshot()
        val x = (frame.width * 0.97f).toInt().coerceAtMost(frame.width - 1)
        var start = -1
        for (y in frame.height - 260 downTo frame.height - 420) {
            if (luminance(frame, x, y) in 18..45) {
                start = y
                break
            }
        }
        if (start < 0) {
            frame.recycle()
            return -1
        }
        var y = start
        while (y > frame.height / 3 && luminance(frame, x, y) in 18..45) y -= 2
        frame.recycle()
        return y
    }

    private fun luminance(frame: Bitmap, x: Int, y: Int): Int {
        val pixel = frame.getPixel(x, y)
        return (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
    }
}
