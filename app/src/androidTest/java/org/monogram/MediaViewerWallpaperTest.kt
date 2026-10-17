package org.monogram

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.monogram.core.ui.R
import org.monogram.core.ui.media.*
import org.monogram.core.ui.theme.MonogramTheme

class MediaViewerWallpaperTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After fun stopPlayback() { compose.runOnIdle { MediaPlaybackHolder.peek()?.stop() } }

    @Test fun sdrPhotoMenuHasNoWallpaperAction() {
        launch(MediaViewerItem("sdr", MediaViewerKind.PHOTO))
        assertNoWallpaperAction()
    }

    @Test fun gainmapPhotoMenuHasNoWallpaperAction() {
        val fixture = java.io.File(compose.activity.cacheDir, "wallpaper-ultra-hdr.jpg")
        val bytes = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .context.assets.open("ultra-hdr.jpg").use { it.readBytes() }
        fixture.writeBytes(bytes)
        try {
            val album = MediaAlbumState(listOf(MediaViewerItem(
                "gainmap", MediaViewerKind.PHOTO, source = MediaSource.Local(fixture),
            )))
            launch(album.current!!, album)
            compose.waitUntil(5_000) { album.current?.width == 256 }
            assertNoWallpaperAction()
        } finally { fixture.delete() }
    }

    private fun assertNoWallpaperAction() {
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.media_more_actions)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.media_action_info)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.media_action_copy_image)).assertExists()
        compose.onNodeWithText("Set as wallpaper").assertDoesNotExist()
    }

    private fun launch(item: MediaViewerItem, suppliedAlbum: MediaAlbumState? = null) {
        compose.setContent {
            MonogramTheme {
                MediaViewerTheme {
                    val session = remember { MediaPlaybackHolder.session(compose.activity) }
                    val album = remember { suppliedAlbum ?: MediaAlbumState(listOf(item)) }
                    MediaViewerShell(album = album, onDismiss = {}, session = session, chatKey = "wallpaper-test")
                }
            }
        }
        compose.waitForIdle()
    }
}
