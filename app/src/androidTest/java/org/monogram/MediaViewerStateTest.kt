package org.monogram

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.monogram.core.ui.R
import org.monogram.core.ui.components.MediaPreviewViewer
import org.monogram.core.ui.theme.MonogramTheme
import java.io.File
import kotlin.math.abs

class MediaViewerStateTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var scenario: ActivityScenario<UiStateTestActivity>? = null
    private var underlyingBacks = 0
    private var recordingFixture by mutableStateOf(false)
    private val fixtures = mutableListOf<File>()

    @After
    fun cleanUp() {
        scenario?.close()
        UiStateTestActivity.content = {}
        fixtures.forEach { it.delete() }
    }

    @Test
    fun photoZoomHiddenChromeAndExpandedCaptionSurviveActivityRecreation() {
        launch(photo())
        preparePhotoState()
        var before: UiStateTestActivity? = null
        scenario!!.onActivity { before = it }
        scenario!!.recreate()
        scenario!!.onActivity { assertNotSame("Activity must actually be recreated", before, it) }
        assertPhotoState()
        assertEquals(0, underlyingBacks)
    }

    @Test
    fun photoStateSurvivesRequestedLandscapeOrientation() {
        launch(photo())
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        awaitOrientation(Configuration.ORIENTATION_PORTRAIT)
        preparePhotoState()
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        awaitOrientation(Configuration.ORIENTATION_LANDSCAPE)
        assertPhotoState()
        assertEquals(0, underlyingBacks)
    }

    @Test
    fun localVideoPausedPositionAndMuteSurviveActivityRecreation() {
        launch(photo())
        val video = recordFixture()
        scenario!!.close()
        launch(video, video = true)
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("media-video-ready")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(label(R.string.media_video_pause)).performClick()
        compose.onNodeWithContentDescription(label(R.string.media_video_mute)).performClick()
        val seek = compose.onNodeWithContentDescription(label(R.string.media_video_seek))
        seek.performTouchInput { click(center) }
        compose.waitUntil(5_000) { playbackPosition() > 500f }
        val position = playbackPosition()
        compose.onNodeWithContentDescription(label(R.string.media_video_play)).assertExists()
        compose.onNodeWithContentDescription(label(R.string.media_video_unmute)).assertExists()
        scenario!!.recreate()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("media-video-ready")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("State test media").assertExists()
        compose.onNodeWithContentDescription(label(R.string.media_video_play)).assertExists()
        compose.onNodeWithContentDescription(label(R.string.media_video_unmute)).assertExists()
        compose.waitUntil(5_000) { abs(playbackPosition() - position) < 300f }
        assertTrue("Paused position must survive recreation", abs(playbackPosition() - position) < 300f)
        assertEquals(0, underlyingBacks)
    }

    private fun launch(file: File, video: Boolean = false) {
        UiStateTestActivity.content = {
            MonogramTheme {
                var visible by rememberSaveable { mutableStateOf(true) }
                BackHandler { underlyingBacks++ }
                Box(Modifier.fillMaxSize()) { Text("Underlying state test chat") }
                var frame by remember { mutableIntStateOf(0) }
                LaunchedEffect(recordingFixture) {
                    while (recordingFixture) {
                        frame++
                        kotlinx.coroutines.delay(50)
                    }
                }
                if (visible) MediaPreviewViewer(
                    file = file,
                    contentDescription = "State test media",
                    stateKey = "state-test-${file.name}",
                    caption = if (recordingFixture) "Frame $frame" else "A long caption for recreation. ".repeat(20),
                    forceVideo = video,
                    loop = video,
                    muted = false,
                    onDismiss = { visible = false },
                )
            }
        }
        scenario = ActivityScenario.launch(UiStateTestActivity::class.java)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("State test media").assertExists()
    }

    private fun preparePhotoState() {
        compose.onNodeWithContentDescription(label(R.string.media_caption_expand)).performClick()
        compose.onNodeWithContentDescription(label(R.string.media_caption_collapse)).assertExists()
        compose.onNodeWithContentDescription("State test media").performTouchInput { doubleClick(center) }
        assertZoom()
        compose.onNodeWithContentDescription("State test media").performTouchInput { advanceEventTime(400); click(center) }
        awaitChrome(false)
        compose.onNodeWithContentDescription(label(R.string.media_preview_close)).assertDoesNotExist()
    }

    private fun assertPhotoState() {
        compose.waitForIdle()
        assertZoom()
        compose.onNodeWithContentDescription(label(R.string.media_preview_close)).assertDoesNotExist()
        compose.onNodeWithContentDescription("State test media").performTouchInput { click(center) }
        awaitChrome(true)
        compose.onNodeWithContentDescription(label(R.string.media_preview_close)).assertExists()
        compose.onNodeWithContentDescription(label(R.string.media_caption_collapse)).assertExists()
        compose.onNodeWithContentDescription(label(R.string.media_caption_expand)).assertDoesNotExist()
        compose.onNodeWithText("Underlying state test chat").assertExists()
    }

    private fun assertZoom() = compose.onNodeWithContentDescription("State test media").assert(
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "250%"),
    )

    private fun playbackPosition(): Float = compose.onNodeWithContentDescription(label(R.string.media_video_seek))
        .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun awaitChrome(visible: Boolean) {
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription(label(R.string.media_preview_close)))
                .fetchSemanticsNodes().isNotEmpty() == visible
        }
    }

    private fun awaitOrientation(expected: Int) {
        compose.waitUntil(10_000) {
            var orientation = Configuration.ORIENTATION_UNDEFINED
            scenario!!.onActivity { orientation = it.resources.configuration.orientation }
            orientation == expected
        }
        compose.waitForIdle()
    }

    private fun label(resource: Int): String = instrumentation.targetContext.getString(resource)

    private fun photo(): File {
        val file = File(instrumentation.targetContext.cacheDir, "viewer-state-photo.png").also(fixtures::add)
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(36, 112, 92))
            drawRect(80f, 80f, 400f, 520f, android.graphics.Paint().apply { color = Color.rgb(240, 214, 88) })
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun recordFixture(): File {
        compose.runOnIdle { recordingFixture = true }
        val shellFile = File(instrumentation.targetContext.getExternalFilesDir(null), "viewer-state-video.mp4").also(fixtures::add)
        val file = File(instrumentation.targetContext.cacheDir, "viewer-state-video.mp4").also(fixtures::add)
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "screenrecord --time-limit 3 ${shellFile.absolutePath}",
        )).use { input ->
            // Advance Compose frames during capture so the fixture has a real timeline.
            repeat(65) {
                compose.mainClock.advanceTimeBy(50)
                android.os.SystemClock.sleep(50)
            }
            input.readBytes()
        }
        compose.runOnIdle { recordingFixture = false }
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "cat ${shellFile.absolutePath}",
        )).use { input -> file.outputStream().use { input.copyTo(it) } }
        assertTrue("Local video fixture must be recorded", file.length() > 0L)
        return file
    }
}
