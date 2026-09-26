package org.monogram

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.monogram.core.ui.media.MediaPlaybackHolder
import org.monogram.core.ui.media.MediaPlaybackSession
import org.monogram.core.ui.media.MediaSource
import org.monogram.core.ui.media.MediaSurface
import org.monogram.core.ui.media.MediaViewerItem
import org.monogram.core.ui.media.MediaViewerKind
import org.monogram.core.ui.media.MediaViewerTheme
import org.monogram.core.ui.media.MiniPlayerBar
import org.monogram.core.ui.theme.MonogramTheme

/**
 * The docked bar owns the video output while it is the active surface, so a collapsed video
 * keeps moving in the tile instead of freezing on a poster.
 */
class MiniPlayerVideoTest {
    @get:Rule val compose = createAndroidComposeRule<PipTestActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun stopSession() {
        compose.runOnIdle { MediaPlaybackHolder.peek()?.stop() }
        compose.waitForIdle()
    }

    @After
    fun tearDown() {
        compose.runOnIdle { MediaPlaybackHolder.peek()?.stop() }
    }

    private fun fixture(name: String): File {
        val target = File(compose.activity.cacheDir, name)
        if (target.exists() && target.length() > 0) return target
        instrumentation.context.assets.open("media/$name").use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return target
    }

    private fun poster(name: String, colour: Int): File {
        val target = File(compose.activity.cacheDir, name)
        if (target.exists()) return target
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { drawColor(colour) }
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return target
    }

    private fun video(): MediaViewerItem = MediaViewerItem(
        id = "mini-v1",
        kind = MediaViewerKind.VIDEO,
        source = MediaSource.Local(fixture("video_short.mp4")),
        durationSeconds = 12,
        senderName = "testing bot",
        dateLabel = "06:00",
        preview = poster("mini-poster.png", Color.rgb(24, 26, 32)),
    )

    @Composable
    private fun Bar() {
        val context = LocalContext.current
        val session = remember(context) { MediaPlaybackHolder.session(context) }
        Box(Modifier.fillMaxSize()) {
            MiniPlayerBar(
                session = session,
                onExpand = {},
                onStop = { session.stop() },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }

    /** The 40.dp tile at the start of the bar, located from the bar's own bounds. */
    private fun tileRect(): android.graphics.Rect {
        val bounds = compose.onNode(
            hasContentDescription("testing bot", substring = true),
        ).fetchSemanticsNode().boundsInWindow
        val density = compose.activity.resources.displayMetrics.density
        val left = (bounds.left + 12f * density).toInt()
        val tile = 40f * density
        val top = (bounds.top + (bounds.height - tile) / 2f).toInt()
        return android.graphics.Rect(left, top, (left + tile).toInt(), (top + tile).toInt())
    }

    private fun tileFrame(name: String): Bitmap {
        val full = instrumentation.uiAutomation.takeScreenshot()
        val rect = tileRect()
        return Bitmap.createBitmap(full, rect.left, rect.top, rect.width(), rect.height()).also { crop ->
            File(compose.activity.cacheDir, name).outputStream().use {
                crop.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            full.recycle()
        }
    }

    private fun differingPixels(first: Bitmap, second: Bitmap): Int {
        var differing = 0
        for (y in 0 until first.height) {
            for (x in 0 until first.width) {
                if (first.getPixel(x, y) != second.getPixel(x, y)) differing++
            }
        }
        return differing
    }

    @Test
    fun collapsedVideoKeepsMovingInTheTile() {
        // The session is main-thread owned: the composition creates it, the main thread drives it.
        compose.setContent { MonogramTheme { MediaViewerTheme { Bar() } } }
        compose.waitForIdle()
        val session = requireNotNull(compose.runOnIdle { MediaPlaybackHolder.peek() })
        compose.runOnIdle {
            session.setQueue(listOf(video()), 0, autoplay = true, startMuted = true)
            session.attachSurface(MediaSurface.MINI_PLAYER)
        }
        compose.waitUntil(15_000) {
            compose.runOnIdle { session.playing && session.positionMs > 600L }
        }
        val first = tileFrame("mini-tile-a.png")
        Thread.sleep(900)
        compose.waitForIdle()
        val second = tileFrame("mini-tile-b.png")
        val differing = differingPixels(first, second)
        val total = first.width * first.height
        first.recycle()
        second.recycle()
        assertTrue(
            "the mini player tile froze on a still frame ($differing of $total pixels changed)",
            differing > total / 50,
        )
    }
}
