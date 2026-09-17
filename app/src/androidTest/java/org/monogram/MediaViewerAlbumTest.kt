package org.monogram

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.monogram.core.ui.media.MediaAlbumState
import org.monogram.core.ui.media.MediaPlaybackHolder
import org.monogram.core.ui.media.MediaSource
import org.monogram.core.ui.media.MediaViewerItem
import org.monogram.core.ui.media.MediaViewerKind
import org.monogram.core.ui.media.MediaViewerShell
import org.monogram.core.ui.media.rememberAlbumState
import org.monogram.core.ui.media.MediaViewerTheme
import org.monogram.core.ui.theme.MonogramTheme
import java.io.File

class MediaViewerAlbumTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var visible by mutableStateOf(true)
    private var albumIndex by mutableStateOf(0)

    @Before
    fun stage() {
        visible = true
        albumIndex = 0
    }

    @After
    fun cleanUp() {
        compose.runOnIdle { MediaPlaybackHolder.peek()?.stop() }
    }

    private fun cacheFixture(name: String): File {
        val target = File(compose.activity.cacheDir, name)
        if (target.exists() && target.length() > 0) return target
        instrumentation.context.assets.open("media/$name").use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        assertTrue("fixture $name must exist", target.length() > 0)
        return target
    }

    private fun photoFixture(name: String, background: Int, accent: Int): File {
        val target = File(compose.activity.cacheDir, name)
        if (target.exists()) return target
        val bitmap = Bitmap.createBitmap(900, 700, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(background)
            drawRect(60f, 60f, 500f, 620f, android.graphics.Paint().apply { color = accent })
        }
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return target
    }

    /** photo, video, photo, video: the album every rule in the viewer is written for. */
    private fun mixedAlbum(): List<MediaViewerItem> = listOf(
        MediaViewerItem(
            id = "p1",
            kind = MediaViewerKind.PHOTO,
            source = MediaSource.Local(photoFixture("album-bright.png", Color.rgb(248, 246, 240), Color.rgb(255, 214, 165))),
            preview = photoFixture("album-bright.png", Color.rgb(248, 246, 240), Color.rgb(255, 214, 165)),
            senderName = "Anna",
            dateLabel = "yesterday",
        ),
        MediaViewerItem(
            id = "v1",
            kind = MediaViewerKind.VIDEO,
            source = MediaSource.Local(cacheFixture("video_short.mp4")),
            durationSeconds = 12,
            senderName = "Anna",
            dateLabel = "yesterday",
            preview = photoFixture("poster-v1.png", Color.rgb(20, 22, 30), Color.rgb(200, 60, 120)),
        ),
        MediaViewerItem(
            id = "p2",
            kind = MediaViewerKind.PHOTO,
            source = MediaSource.Local(photoFixture("album-dark.png", Color.rgb(12, 10, 16), Color.rgb(60, 30, 70))),
            preview = photoFixture("album-dark.png", Color.rgb(12, 10, 16), Color.rgb(60, 30, 70)),
            caption = "Item 3 caption only",
            senderName = "Anna",
            dateLabel = "yesterday",
        ),
        MediaViewerItem(
            id = "v2",
            kind = MediaViewerKind.VIDEO,
            source = MediaSource.Local(cacheFixture("video_medium.mp4")),
            durationSeconds = 46,
            senderName = "Anna",
            dateLabel = "yesterday",
            preview = photoFixture("poster-v2.png", Color.rgb(30, 30, 40), Color.rgb(60, 160, 200)),
        ),
    )

    private fun launch(album: List<MediaViewerItem>, startIndex: Int) {
        compose.setContent {
            MonogramTheme {
                val context = LocalContext.current
                val session = remember(context) { MediaPlaybackHolder.session(context) }
                Box(Modifier.fillMaxSize()) { Text("Underlying chat") }
                if (visible) {
                    MediaViewerTheme {
                        MediaViewerShell(
                            album = remember(album, startIndex) { MediaAlbumState(album, startIndex) },
                            onDismiss = { visible = false },
                            session = session,
                            chatKey = "test-chat",
                            onIndexChange = { albumIndex = it },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun mixedAlbumShowsCounterKindAndFilmstrip() {
        launch(mixedAlbum(), startIndex = 0)
        compose.onNodeWithText("Anna").assertExists()
        compose.onNodeWithText("yesterday · 1/4").assertExists()
        capture("E-mixed-photo")
        compose.onRoot().performTouchInput { swipe(centerRight, centerLeft, 220) }
        compose.waitUntil(10_000) { albumIndex == 1 }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("media-video-ready")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("yesterday · 2/4 · video").assertExists()
        capture("E-mixed-video")
    }

    @Test
    fun perItemCaptionsSwapWithTheAlbum() {
        launch(listOf(mixedAlbum()[0], mixedAlbum()[2], mixedAlbum()[1]), startIndex = 0)
        compose.onNodeWithText("Item 3 caption only").assertDoesNotExist()
        compose.onRoot().performTouchInput { swipe(centerRight, centerLeft, 220) }
        compose.waitUntil(5_000) { albumIndex == 1 }
        compose.onNodeWithText("Item 3 caption only").assertExists()
        compose.onRoot().performTouchInput { swipe(centerLeft, centerRight, 220) }
        compose.waitUntil(5_000) { albumIndex == 0 }
        compose.onNodeWithText("Item 3 caption only").assertDoesNotExist()
    }

    @Test
    fun captionHidesWithTheChromeAndReturnsWithIt() {
        val captioned = mixedAlbum().toMutableList()
        captioned[1] = captioned[1].copy(caption = "A caption that belongs to the chrome")
        launch(captioned, startIndex = 1)
        val close = compose.activity.getString(org.monogram.core.ui.R.string.media_preview_close)
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("media-video-ready")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("A caption that belongs to the chrome").assertExists()
        capture("C-caption-with-chrome")
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasContentDescription(close)).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("A caption that belongs to the chrome").assertDoesNotExist()
        capture("C-caption-hidden-with-chrome")
        compose.onNode(hasTestTag("media-video-ready")).performTouchInput { click(center) }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription(close)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("A caption that belongs to the chrome").assertExists()
    }

    @Test
    fun captionSheetShowsMetadataAndMaterialActions() {
        launch(listOf(mixedAlbum()[2]), startIndex = 0)
        compose.onNodeWithContentDescription(compose.activity.getString(org.monogram.core.ui.R.string.media_caption_expand))
            .performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(
                hasContentDescription(compose.activity.getString(org.monogram.core.ui.R.string.media_caption_collapse)),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Item 3 caption only").assertExists()
        compose.onNodeWithText(compose.activity.getString(org.monogram.core.ui.R.string.media_action_show_in_chat)).assertExists()
        compose.onNodeWithText(compose.activity.getString(org.monogram.core.ui.R.string.media_action_copy_caption)).assertExists()
        capture("C-caption-sheet")
    }

    @Test
    fun videoChromeAutoHidesAndComesBackWithATick() {
        launch(mixedAlbum(), startIndex = 1)
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("media-video-ready")).fetchSemanticsNodes().isNotEmpty()
        }
        val close = compose.activity.getString(org.monogram.core.ui.R.string.media_preview_close)
        compose.onNodeWithContentDescription(close).assertExists()
        compose.waitUntil(8_000) {
            compose.onAllNodes(hasContentDescription(close)).fetchSemanticsNodes().isEmpty()
        }
        capture("B-tick-only")
        compose.onNodeWithText("Underlying chat").assertExists()
        compose.onNode(hasTestTag("media-video-ready")).performTouchInput { click(center) }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription(close)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(close).assertExists()
    }

    @Test
    fun seekingMovesThePlayheadAndBufferedRangeIsExposed() {
        launch(mixedAlbum(), startIndex = 3)
        val seekLabel = compose.activity.getString(org.monogram.core.ui.R.string.media_video_seek)
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasContentDescription(seekLabel)).fetchSemanticsNodes().isNotEmpty()
        }
        val seek = compose.onNodeWithContentDescription(seekLabel)
        seek.assertExists()
        compose.waitForIdle()
        seek.performTouchInput { click(Offset(width * 0.6f, center.y)) }
        compose.waitUntil(5_000) { playbackPosition() > 1_000f }
        seek.performTouchInput { swipe(centerLeft, centerRight, 300) }
        compose.waitForIdle()
        capture("B-seek")
    }

    @Test
    fun playPauseMorphTogglesDescription() {
        launch(mixedAlbum(), startIndex = 1)
        val pauseLabel = compose.activity.getString(org.monogram.core.ui.R.string.media_video_pause)
        val playLabel = compose.activity.getString(org.monogram.core.ui.R.string.media_video_play)
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasContentDescription(pauseLabel)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(pauseLabel).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription(playLabel)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(playLabel).assertExists()
        compose.onNodeWithContentDescription(playLabel).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription(pauseLabel)).fetchSemanticsNodes().isNotEmpty()
        }
        capture("B-hero-controls")
    }

    @Test
    fun overflowStaysOpenAndItsActionsApply() {
        launch(mixedAlbum(), startIndex = 1)
        val session = MediaPlaybackHolder.session(compose.activity)
        val more = compose.activity.getString(org.monogram.core.ui.R.string.media_more_actions)
        compose.waitUntil(10_000) { session.playing }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasContentDescription(more)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(more).performTouchInput { click() }
        val speedLabel = compose.activity.getString(org.monogram.core.ui.R.string.media_menu_speed, "1×")
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(speedLabel)).fetchSemanticsNodes().isNotEmpty()
        }
        Thread.sleep(3_800)
        compose.waitForIdle()
        compose.onNodeWithText(speedLabel).assertExists()
        compose.onNodeWithContentDescription(compose.activity.getString(org.monogram.core.ui.R.string.media_preview_close))
            .assertExists()
        capture("B-overflow-open")

        compose.onNodeWithText(speedLabel).performClick()
        val doubleSpeed = compose.activity.getString(org.monogram.core.ui.R.string.media_menu_speed, "2×")
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("2×")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("2×").performClick()
        compose.waitUntil(5_000) { session.speed == 2f }
        compose.onNodeWithText("2×").assertDoesNotExist()
    }

    @Test
    fun aRebuiltItemListDoesNotRewindTheAlbum() {
        var tick by mutableIntStateOf(0)
        compose.setContent {
            MonogramTheme {
                val context = LocalContext.current
                val session = remember(context) { MediaPlaybackHolder.session(context) }
                val items = mixedAlbum().map { it }
                tick
                MediaViewerTheme {
                    MediaViewerShell(
                        album = rememberAlbumState(items, startIndex = 0),
                        onDismiss = { visible = false },
                        session = session,
                        chatKey = "rebuild-test",
                        onIndexChange = { albumIndex = it },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("yesterday · 1/4").assertExists()

        compose.onRoot().performTouchInput { swipe(centerRight, centerLeft, 220) }
        compose.waitUntil(5_000) { albumIndex == 1 }
        compose.onNodeWithText("yesterday · 2/4 · video").assertExists()

        repeat(3) {
            compose.runOnIdle { tick++ }
            compose.waitForIdle()
        }
        compose.onNodeWithText("yesterday · 2/4 · video").assertExists()
        compose.onNodeWithText("yesterday · 1/4").assertDoesNotExist()
    }

    private fun playbackPosition(): Float = compose
        .onNodeWithContentDescription(compose.activity.getString(org.monogram.core.ui.R.string.media_video_seek))
        .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun capture(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val directory = File(compose.activity.getExternalFilesDir(null), "viewer-qa").apply { mkdirs() }
        val file = File(directory, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        instrumentation.uiAutomation.executeShellCommand("mkdir -p /sdcard/Download/monogram-viewer-qa").close()
        instrumentation.uiAutomation.executeShellCommand(
            "cp ${file.absolutePath} /sdcard/Download/monogram-viewer-qa/$name.png",
        ).close()
    }
}
