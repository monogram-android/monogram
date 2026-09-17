package org.monogram

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.monogram.core.ui.R
import org.monogram.core.ui.media.MediaAlbumState
import org.monogram.core.ui.media.MediaPlaybackHolder
import org.monogram.core.ui.media.MediaPlaybackSession
import org.monogram.core.ui.media.MediaSource
import org.monogram.core.ui.media.MediaViewerActions
import org.monogram.core.ui.media.MediaViewerItem
import org.monogram.core.ui.media.MediaViewerKind
import org.monogram.core.ui.media.MediaViewerShell
import org.monogram.core.ui.media.MediaViewerTheme
import org.monogram.core.ui.media.MiniPlayerBar
import org.monogram.core.ui.theme.MonogramTheme
import java.io.File

class MediaViewerBackgroundTest {
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

    /** photo, video, photo, video: the mixed album the background rules are written for. */
    private fun mixedAlbum(): List<MediaViewerItem> = listOf(
        MediaViewerItem("p1", MediaViewerKind.PHOTO, MediaSource.Local(poster("bg-p1.png", Color.rgb(240, 240, 245))), senderName = "Anna"),
        MediaViewerItem(
            "v1", MediaViewerKind.VIDEO, MediaSource.Local(fixture("video_short.mp4")),
            durationSeconds = 12, senderName = "Anna", preview = poster("bg-v1.png", Color.rgb(30, 32, 44)),
        ),
        MediaViewerItem("p2", MediaViewerKind.PHOTO, MediaSource.Local(poster("bg-p2.png", Color.rgb(16, 14, 20))), senderName = "Anna"),
        MediaViewerItem(
            "v2", MediaViewerKind.VIDEO, MediaSource.Local(fixture("video_medium.mp4")),
            durationSeconds = 46, senderName = "Anna", preview = poster("bg-v2.png", Color.rgb(40, 44, 60)),
        ),
    )

    /** Mirrors the app wiring: the viewer replaces the chat scaffold, the mini player never coexists with it. */
    @Composable
    private fun ChatScaffold(session: MediaPlaybackSession, items: List<MediaViewerItem>, startIndex: Int) {
        var viewerOpen by remember { mutableStateOf(true) }
        Box(Modifier.fillMaxSize()) {
            Text("Chat scaffold", Modifier.align(Alignment.TopCenter))
            if (viewerOpen) {
                MediaViewerShell(
                    album = remember(items, startIndex) { MediaAlbumState(items, startIndex) },
                    onDismiss = { viewerOpen = false },
                    session = session,
                    chatKey = "bg-test",
                    headerTitle = "Anna",
                    actions = MediaViewerActions(
                        onListenInBackground = {
                            session.listenInBackground()
                            viewerOpen = false
                        },
                        canPictureInPicture = true,
                    ),
                )
            } else {
                MiniPlayerBar(
                    session = session,
                    onExpand = { viewerOpen = true },
                    onStop = { session.stop() },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    private fun launch(items: List<MediaViewerItem>, startIndex: Int) {
        compose.setContent {
            MonogramTheme {
                val context = LocalContext.current
                val session = remember(context) { MediaPlaybackHolder.session(context) }
                MediaViewerTheme { ChatScaffold(session, items, startIndex) }
            }
        }
        compose.waitForIdle()
    }

    /** Brings the chrome back if the 3s auto-hide already fired, like a real user would. */
    private fun ensureChrome() {
        val more = compose.activity.getString(R.string.media_more_actions)
        if (compose.onAllNodes(hasContentDescription(more)).fetchSemanticsNodes().isEmpty()) {
            compose.onNode(hasTestTag("media-video-ready")).performTouchInput { click(center) }
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasContentDescription(more)).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    @Test
    fun listenInBackgroundKeepsAudioAndShowsMiniPlayer() {
        launch(mixedAlbum(), startIndex = 1)
        val session = MediaPlaybackHolder.session(compose.activity)
        compose.waitUntil(10_000) { session.playing }
        val listenLabel = compose.activity.getString(R.string.media_menu_listen_background)
        ensureChrome()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.media_more_actions))
            .performTouchInput { click() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(listenLabel)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(listenLabel).performClick()
        compose.waitUntil(5_000) { session.audioOnly }
        compose.waitForIdle()
        assertTrue("the video surface must be gone", session.audioOnly)
        assertTrue("audio keeps playing", session.playing)
        compose.onNodeWithText("Chat scaffold").assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.media_mini_player_pause))
            .assertIsDisplayed()
        capture("F4-listen-in-background")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.media_mini_player_pause)).performClick()
        compose.waitUntil(5_000) { !session.playing }
        capture("F6-mini-player-paused")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.media_mini_player_collapse)).performClick()
        compose.waitUntil(5_000) { MediaPlaybackHolder.peek()?.current == null }
        assertFalse("closing the mini player stops the session", session.playing)
    }

    @Test
    fun miniPlayerKeepsTheSameItemAndTimestamp() {
        launch(mixedAlbum(), startIndex = 1)
        val session = MediaPlaybackHolder.session(compose.activity)
        compose.waitUntil(10_000) { session.playing }
        compose.runOnIdle { session.seekTo(4_000L) }
        compose.waitUntil(5_000) { session.positionMs >= 3_500L }
        val listenLabel = compose.activity.getString(R.string.media_menu_listen_background)
        ensureChrome()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.media_more_actions))
            .performTouchInput { click() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(listenLabel)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(listenLabel).performClick()
        compose.waitUntil(5_000) { session.audioOnly }
        compose.waitForIdle()
        assertEquals("v1", session.current?.id)
        assertTrue("position is kept, not restarted", session.positionFor("v1") >= 3_500L)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.media_mini_player_pause)).performClick()
        compose.waitForIdle()
        assertNotNull(session.current)
    }

    @Test
    fun videoQueueWalksVideosOnlyAndStopsAtTheEnd() {
        launch(mixedAlbum(), startIndex = 1)
        val session = MediaPlaybackHolder.session(compose.activity)
        compose.waitUntil(10_000) { session.current?.id == "v1" }
        assertTrue(session.selectNextVideo())
        compose.waitUntil(5_000) { session.current?.id == "v2" }
        assertEquals("the notification's next skips the photo in between", "v2", session.current?.id)
        assertFalse("there is no video after the last one", session.selectNextVideo())
        assertEquals("and it does not fall back to an unrelated item", "v2", session.current?.id)
    }

    @Test
    fun mixedAlbumIsNeverTreatedAsVideoOnly() {
        launch(mixedAlbum(), startIndex = 1)
        val session = MediaPlaybackHolder.session(compose.activity)
        compose.waitUntil(10_000) { session.current?.id == "v1" }
        assertFalse("a mixed album must not auto-advance into a photo", session.isAlbumVideoOnly)
        assertTrue("and the player must stop instead of rolling on", session.pausesAtEndOfMixedVideo)
        assertTrue(session.hasAlbum)
    }

    @Test
    fun videoAfterAPhotoStartsMutedAndAfterAVideoInheritsSound() {
        launch(mixedAlbum(), startIndex = 1)
        val session = MediaPlaybackHolder.session(compose.activity)
        compose.waitUntil(10_000) { session.current?.id == "v1" }
        compose.runOnIdle {
            session.setQueue(mixedAlbum(), 1, autoplay = true, startMuted = true, preserveUserMute = false)
        }
        compose.waitUntil(5_000) { session.muted }
        compose.runOnIdle {
            session.setQueue(mixedAlbum(), 3, autoplay = true, startMuted = false, preserveUserMute = false)
        }
        compose.waitUntil(5_000) { !session.muted }
        compose.runOnIdle {
            session.setQueue(mixedAlbum(), 3, autoplay = true, startMuted = true, preserveUserMute = true)
        }
        compose.waitForIdle()
        assertFalse("re-entering the same item never flips the user's mute choice", session.muted)
    }

    @Test
    fun enteringPictureInPictureKeepsTheSameSession() {
        val supportsPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            compose.activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        assertTrue("this emulator image must support PiP", supportsPip)
        launch(mixedAlbum(), startIndex = 1)
        val session = MediaPlaybackHolder.session(compose.activity)
        compose.waitUntil(10_000) { session.playing }
        compose.activityRule.scenario.onActivity { activity ->
            activity.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
        compose.waitUntil(10_000) { compose.activity.isInPictureInPictureMode }
        assertTrue("PiP must keep the same player, not a second decoder", MediaPlaybackHolder.peek() === session)
        assertTrue("the session is still alive in PiP", session.current != null)
        capture("F2-picture-in-picture")
        compose.activityRule.scenario.onActivity { it.setPictureInPictureParams(PictureInPictureParams.Builder().build()) }
    }

    @Test
    fun theShippedActivityDeclaresPictureInPictureSupport() {
        val info = compose.activity.packageManager.getActivityInfo(
            android.content.ComponentName(compose.activity.packageName, "org.monogram.MainActivity"),
            0,
        )
        assertTrue("MainActivity must declare supportsPictureInPicture", info.flags and (1 shl 20) != 0)
    }

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
