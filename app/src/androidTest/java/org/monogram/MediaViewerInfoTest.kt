package org.monogram

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.monogram.core.ui.R
import org.monogram.core.ui.media.LocalMediaViewerMotion
import org.monogram.core.ui.media.MediaAlbumState
import org.monogram.core.ui.media.MediaHdr
import org.monogram.core.ui.media.MediaPlaybackHolder
import org.monogram.core.ui.media.MediaSource
import org.monogram.core.ui.media.MediaViewerItem
import org.monogram.core.ui.media.MediaViewerKind
import org.monogram.core.ui.media.MediaViewerShell
import org.monogram.core.ui.media.MediaViewerTheme
import org.monogram.core.ui.theme.MonogramTheme
import java.io.File

class MediaViewerInfoTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After
    fun stopPlayback() {
        compose.runOnIdle { MediaPlaybackHolder.peek()?.stop() }
    }

    @Test
    fun videoHdrIsNotReportedAsPhotoGainmap() {
        val album = MediaAlbumState(listOf(MediaViewerItem(
            id = "hdr-video", kind = MediaViewerKind.VIDEO,
            fileName = "movie.mp4", fileSize = 2048, width = 1920, height = 1080,
            durationSeconds = 90, dateLabel = "Fixture date",
        )))
        launch(album)
        compose.runOnIdle { album.setHdr("hdr-video", MediaHdr.Video("HDR10")) }
        openInfo()
        assertRow("hdr", R.string.media_info_yes)
        assertRow("gainmap", R.string.media_info_no)
        compose.onNodeWithTag("media_info_dimensions").assertTextContains("1920 × 1080")
        compose.onNodeWithTag("media_info_duration").assertTextContains("01:30")
        compose.onNodeWithTag("media_info_date").assertTextContains("Fixture date")
    }

    @Test
    fun detectedGainmapReportsHdrAndGainmap() {
        val item = MediaViewerItem(id = "gainmap-photo", kind = MediaViewerKind.PHOTO)
        val album = MediaAlbumState(listOf(item))
        launch(album)
        compose.runOnIdle { album.setHdr(item.id, MediaHdr.GainMap) }
        openInfo()
        assertRow("hdr", R.string.media_info_yes)
        assertRow("gainmap", R.string.media_info_yes)
    }

    @Test
    fun missingAndInvalidMetadataRemainUnknownNotInventedFromIdOrAspectRatio() {
        launch(MediaAlbumState(listOf(MediaViewerItem(
            id = "not-a-file-name.jpg", kind = MediaViewerKind.UNKNOWN,
            fileName = "  ", fileSize = 0, width = 0, height = 100,
            aspectRatio = 1.777f, durationSeconds = -1, dateMillis = 0,
        ))))
        openInfo()
        listOf("name", "size", "dimensions", "duration", "date").forEach {
            assertRow(it, R.string.media_info_unknown)
        }
        assertRow("hdr", R.string.media_info_no)
        assertRow("gainmap", R.string.media_info_no)
    }

    @Test
    fun infoUsesNavigatedPageRatherThanSelectedSubset() {
        val first = MediaViewerItem(id = "first", kind = MediaViewerKind.PHOTO, fileName = "selected.jpg", width = 1, height = 2)
        val second = MediaViewerItem(id = "second", kind = MediaViewerKind.PHOTO, fileName = "current.jpg", width = 640, height = 480)
        val album = MediaAlbumState(listOf(first, second))
        album.toggleSelection(first.id)
        launch(album)
        compose.onRoot().performTouchInput { swipe(centerRight, centerLeft, 220) }
        compose.waitUntil(5_000) { album.index == 1 }
        openInfo()
        compose.onNodeWithTag("media_info_name").assertTextContains("current.jpg")
        compose.onNodeWithTag("media_info_dimensions").assertTextContains("640 × 480")
        compose.runOnIdle { assertEquals(listOf(first), album.selectedItems()) }
    }

    @Test
    fun decodedPhotoDimensionsOverrideWrongDeclaredDimensionsAndSurviveAlbumUpdate() {
        val file = File(compose.activity.cacheDir, "viewer-info-dimensions.png")
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        val item = MediaViewerItem(
            id = "decoded", kind = MediaViewerKind.PHOTO,
            source = MediaSource.Local(file), width = 1, height = 2,
        )
        val album = MediaAlbumState(listOf(item))
        launch(album)
        compose.waitUntil(5_000) { album.current?.width == 48 && album.current?.height == 32 }
        compose.runOnIdle { album.update(listOf(item)) }
        openInfo()
        compose.onNodeWithTag("media_info_dimensions").assertTextContains("48 × 32")
        assertRow("name", R.string.media_info_unknown)
    }

    private fun assertRow(tag: String, resource: Int) {
        compose.onNodeWithTag("media_info_$tag").performScrollTo()
            .assertTextContains(compose.activity.getString(resource))
    }

    private fun openInfo() {
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.media_more_actions)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.media_action_info)).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun launch(album: MediaAlbumState) {
        compose.setContent {
            MonogramTheme {
                MediaViewerTheme {
                    CompositionLocalProvider(LocalMediaViewerMotion provides false) {
                        val session = remember { MediaPlaybackHolder.session(compose.activity) }
                        MediaViewerShell(album = album, onDismiss = {}, session = session, chatKey = "info-test")
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
