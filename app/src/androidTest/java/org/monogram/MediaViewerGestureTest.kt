package org.monogram

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasText
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasTestTag
import androidx.media3.datasource.DataSource
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.monogram.core.ui.components.MediaPreviewViewer
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.core.common.Outcome
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.network.http.TelegramChunkFetcher
import org.monogram.network.http.TelegramVideoDataSource
import java.io.File

class MediaViewerGestureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var visible by mutableStateOf(true)
    private var underlyingBacks = 0
    private var streamFactory by mutableStateOf<DataSource.Factory?>(null)

    @Before
    fun showViewer() {
        if (InstrumentationRegistry.getArguments().getString("landscape") == "true") {
            compose.activityRule.scenario.onActivity {
                it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            compose.waitUntil(5_000) {
                compose.activity.resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
        }
        val file = File(compose.activity.cacheDir, "viewer-gesture-test.png")
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(36, 112, 92))
            val paint = android.graphics.Paint().apply { color = Color.rgb(240, 214, 88) }
            drawRect(80f, 80f, 400f, 520f, paint)
            paint.color = Color.rgb(193, 72, 102)
            drawCircle(590f, 300f, 140f, paint)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        compose.setContent {
            MonogramTheme(darkTheme = InstrumentationRegistry.getArguments().getString("light") != "true") {
                BackHandler { underlyingBacks++ }
                Box(Modifier.fillMaxSize()) { Text("Underlying chat") }
                if (visible) MediaPreviewViewer(
                    file = if (streamFactory == null) file else null,
                    uri = if (streamFactory != null) Uri.parse("telegram://media/test") else null,
                    forceVideo = streamFactory != null,
                    videoDataSourceFactory = streamFactory,
                    contentDescription = "Test photo",
                    caption = "A photo caption that remains readable above the navigation bar",
                    onDismiss = { visible = false },
                )
            }
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val color = shot.getPixel(shot.width / 2, shot.height / 2)
            shot.recycle()
            Color.green(color) > 60
        }
    }

    @Test
    fun shortSwipeAndZoomDoNotDismissUnderlyingChat() {
        val photo = compose.onNodeWithContentDescription("Test photo")
        photo.performTouchInput {
            swipe(center, center + Offset(0f, 24f), durationMillis = 300)
        }
        photo.assertExists()
        photo.performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        photo.performTouchInput {
            swipe(center, Offset(center.x, height * 0.9f), durationMillis = 500)
        }
        photo.assertExists()
        compose.runOnIdle { assertEquals(0, underlyingBacks) }
        capture("viewer-zoom.png")
    }

    @Test
    fun offCenterDoubleTapKeepsRenderedImageAtTheFocalPointAndHidesBadge() {
        assertRenderedFocalPoint(pinch = false)
    }

    @Test
    fun offCenterPinchKeepsRenderedImageAtTheFocalPointAndHidesBadge() {
        assertRenderedFocalPoint(pinch = true)
    }

    private fun assertRenderedFocalPoint(pinch: Boolean) {
        val photo = compose.onNodeWithContentDescription("Test photo")
        val bounds = photo.fetchSemanticsNode().boundsInWindow
        val fit = minOf(bounds.width / 800f, bounds.height / 600f)
        // Image (360, 100) is inside yellow, near its top and right green boundaries.
        val focal = Offset(bounds.width / 2f - 40f * fit, bounds.height / 2f - 200f * fit)
        val global = bounds.topLeft + focal
        fun awaitBoundary(scale: Float) {
            compose.waitUntil(5_000) {
                val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                try {
                    fun matches(dx: Float, expected: Int, dy: Float = 0f): Boolean {
                        val x = (global.x + dx * fit * scale).roundToInt()
                        val y = (global.y + dy * fit * scale).roundToInt()
                        return (-2..2).all { offset ->
                            val actual = shot.getPixel(x, y + offset)
                            abs(Color.red(actual) - Color.red(expected)) < 20 &&
                                abs(Color.green(actual) - Color.green(expected)) < 20 &&
                                abs(Color.blue(actual) - Color.blue(expected)) < 20
                        }
                    }
                    matches(0f, Color.rgb(240, 214, 88)) &&
                        matches(28f, Color.rgb(240, 214, 88)) &&
                        matches(52f, Color.rgb(36, 112, 92)) &&
                        matches(0f, Color.rgb(240, 214, 88), 28f) &&
                        matches(0f, Color.rgb(36, 112, 92), -24f)
                } finally {
                    shot.recycle()
                }
            }
        }
        awaitBoundary(1f)
        photo.performTouchInput {
            if (pinch) {
                val start = 40f * fit
                val end = 100f * fit
                down(0, focal - Offset(start, 0f))
                down(1, focal + Offset(start, 0f))
                repeat(20) { step ->
                    val radius = start + (end - start) * (step + 1) / 20f
                    updatePointerTo(0, focal - Offset(radius, 0f))
                    updatePointerTo(1, focal + Offset(radius, 0f))
                    move(delayMillis = 16)
                }
                up(0)
                up(1)
            } else {
                doubleClick(focal)
            }
        }
        compose.waitUntil(5_000) {
            photo.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
                .removeSuffix("%").toInt() > 180
        }
        val percent = photo.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        if (!pinch) assertEquals("250%", percent)
        awaitBoundary(percent.removeSuffix("%").toInt() / 100f)
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(percent)).fetchSemanticsNodes().isEmpty()
        }
        awaitBoundary(percent.removeSuffix("%").toInt() / 100f)
        photo.assertExists()
        compose.runOnIdle { assertEquals(0, underlyingBacks) }
    }

    @Test
    fun dismissSwipeOnlyClosesViewer() {
        compose.onNodeWithContentDescription("Test photo").performTouchInput {
            swipe(Offset(center.x, height * 0.3f), Offset(center.x, height * 0.6f), 500)
        }
        compose.waitUntil(5_000) { !visible }
        compose.onNodeWithText("Underlying chat").assertExists()
        compose.runOnIdle { assertEquals(0, underlyingBacks) }
    }

    @Test
    fun upwardSwipeDoesNotDismissViewer() {
        compose.onNodeWithContentDescription("Test photo").performTouchInput {
            swipe(Offset(center.x, height * 0.8f), Offset(center.x, height * 0.25f), 500)
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Test photo").assertExists()
        compose.runOnIdle { assertEquals(0, underlyingBacks) }
    }

    @Test
    fun systemBackOnlyClosesViewer() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { !visible }
        compose.onNodeWithText("Underlying chat").assertExists()
        compose.runOnIdle { assertEquals(0, underlyingBacks) }
    }

    @Test
    fun streamedVideoRendersAndDismissesWithoutClosingChat() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val recording = File(compose.activity.cacheDir, "viewer-test.mp4")
        instrumentation.context.assets.open("media/video_short.mp4").use { input ->
            recording.outputStream().use { input.copyTo(it) }
        }
        assertTrue(recording.length() > 0)
        val message = Message(
            id = MessageId(PeerId(42), 1), senderId = null, text = null,
            date = 0, outgoing = false, fileSize = recording.length(), mediaKind = "video",
        )
        compose.runOnIdle {
            streamFactory = TelegramVideoDataSource.Factory(
                message, File(compose.activity.cacheDir, "viewer-stream-test"),
                TelegramChunkFetcher { _, _, destination, offset ->
                    java.io.RandomAccessFile(recording, "r").use { input ->
                        input.seek(offset)
                        val bytes = ByteArray(TelegramVideoDataSource.PART_SIZE.toInt())
                        val count = input.read(bytes)
                        File(destination).writeBytes(bytes.copyOf(count.coerceAtLeast(0)))
                    }
                    Outcome.Ok(destination)
                },
            )
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("media-video-ready")).fetchSemanticsNodes().isNotEmpty()
        }
        var renderedFrame: Bitmap? = null
        compose.waitUntil(5_000) {
            val frame = instrumentation.uiAutomation.takeScreenshot()
            if (coloredPixelCount(frame) > 100) {
                renderedFrame = frame
                true
            } else {
                frame.recycle()
                false
            }
        }
        val directory = File(compose.activity.getExternalFilesDir(null), "viewer-qa").apply { mkdirs() }
        renderedFrame!!.let { frame ->
            File(directory, "viewer-video.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
            frame.recycle()
        }
        compose.onNode(isDialog()).performTouchInput {
            swipe(Offset(center.x, height * 0.25f), Offset(center.x, height * 0.8f), 500)
        }
        compose.waitUntil(5_000) { !visible }
        compose.runOnIdle { assertEquals(0, underlyingBacks) }
        recording.delete()
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertTrue("Media must remain rendered", coloredPixelCount(bitmap) > 100)
        val directory = File(compose.activity.getExternalFilesDir(null), "viewer-qa").apply { mkdirs() }
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun coloredPixelCount(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height step 20) {
            for (x in 0 until bitmap.width step 20) {
                val pixel = bitmap.getPixel(x, y)
                if (maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) -
                    minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 65) count++
            }
        }
        return count
    }
}
