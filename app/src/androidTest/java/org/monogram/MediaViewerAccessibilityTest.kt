package org.monogram

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
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

class MediaViewerAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After
    fun stopPlayback() {
        compose.runOnIdle { MediaPlaybackHolder.peek()?.stop() }
    }

    @Test
    fun everyPageKindAnnouncesOwnPositionKindSenderAndDateAndHeaderSeparatesCounter() {
        val items = MediaViewerKind.entries.mapIndexed { index, kind ->
            MediaViewerItem(
                id = "page-$index", kind = kind,
                senderName = "Sender $index", dateLabel = "Date $index",
            )
        }
        val album = MediaAlbumState(items)
        launch(album)
        items.forEachIndexed { index, item ->
            compose.runOnIdle { album.index = index }
            compose.waitForIdle()
            compose.onNodeWithContentDescription(label(item, index, items.size)).assertIsDisplayed()
            compose.onNodeWithText(position(index, items.size)).assertIsDisplayed()
            compose.onNodeWithText(item.dateLabel!!).assertIsDisplayed()
        }
    }

    @Test
    fun singleItemHasNoVisibleCounterButRetainsAccessiblePosition() {
        val item = MediaViewerItem(
            id = "single", kind = MediaViewerKind.PHOTO,
            senderName = "Sender", dateLabel = "Fixture date",
        )
        launch(MediaAlbumState(listOf(item)))
        compose.onNodeWithText(position(0, 1)).assertDoesNotExist()
        compose.onNodeWithText("Fixture date").assertIsDisplayed()
        compose.onNodeWithContentDescription(label(item, 0, 1)).assertIsDisplayed()
    }

    @Test
    fun detectedVideoHdrUpdatesAnnouncementAndFollowingPageHasNoHdr() {
        val video = MediaViewerItem(
            id = "video", kind = MediaViewerKind.VIDEO,
            senderName = "Video sender", dateLabel = "Video date",
        )
        val next = MediaViewerItem(
            id = "next", kind = MediaViewerKind.VIDEO_NOTE,
            senderName = "Next sender", dateLabel = "Next date",
        )
        val album = MediaAlbumState(listOf(video, next))
        launch(album)
        val sdrLabel = label(video, 0, 2)
        compose.onNodeWithContentDescription(sdrLabel).assertIsDisplayed()
        compose.runOnIdle { album.setHdr(video.id, MediaHdr.Video("HDR10")) }
        compose.onNodeWithContentDescription(label(video.copy(hdr = MediaHdr.Video("HDR10")), 0, 2)).assertIsDisplayed()
        compose.onNodeWithContentDescription(sdrLabel).assertDoesNotExist()
        compose.runOnIdle { album.index = 1 }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(label(next, 1, 2)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.media_info_hdr)).assertDoesNotExist()
    }

    @Test
    @SdkSuppress(minSdkVersion = 34)
    fun decodedGainmapIsAnnouncedAndSdrPageAnnouncementChanges() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(compose.activity.cacheDir, "viewer-accessibility-ultra-hdr.jpg")
        instrumentation.context.assets.open("ultra-hdr.jpg").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val photo = MediaViewerItem(
            id = "gainmap", kind = MediaViewerKind.PHOTO,
            source = MediaSource.Local(file), senderName = "Photo sender", dateLabel = "Photo date",
        )
        val next = MediaViewerItem(
            id = "sdr", kind = MediaViewerKind.PHOTO,
            senderName = "SDR sender", dateLabel = "SDR date",
        )
        val album = MediaAlbumState(listOf(photo, next))
        launch(album)
        compose.waitUntil(10_000) { album.items.first().hdr == MediaHdr.GainMap }
        compose.onNodeWithContentDescription(label(photo.copy(hdr = MediaHdr.GainMap), 0, 2)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(MediaHdr.None, photo.hdr)
            album.index = 1
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(label(next, 1, 2)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.media_info_hdr)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(MediaHdr.GainMap, album.items.first().hdr)
        }
    }

    @Test
    fun adjacentVideoPageDoesNotReuseCurrentPageAnnouncement() {
        val first = MediaViewerItem(id = "first", kind = MediaViewerKind.VIDEO, senderName = "First", dateLabel = "First date")
        val second = MediaViewerItem(id = "second", kind = MediaViewerKind.VIDEO_NOTE, senderName = "Second", dateLabel = "Second date")
        launch(MediaAlbumState(listOf(first, second)))
        compose.onAllNodes(
            hasContentDescription(label(first, 0, 2)),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().let { assertEquals(1, it.size) }
    }

    private fun launch(album: MediaAlbumState) {
        compose.setContent {
            MonogramTheme {
                MediaViewerTheme {
                    CompositionLocalProvider(LocalMediaViewerMotion provides false) {
                        val session = remember { MediaPlaybackHolder.session(compose.activity) }
                        MediaViewerShell(album = album, onDismiss = {}, session = session, chatKey = "accessibility-test")
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun position(index: Int, count: Int): String =
        compose.activity.getString(R.string.media_viewer_page_position, index + 1, count)

    private fun label(item: MediaViewerItem, index: Int, count: Int): String {
        val kind = compose.activity.getString(when (item.kind) {
            MediaViewerKind.PHOTO -> R.string.media_badge_photo_lower
            MediaViewerKind.VIDEO -> R.string.media_badge_video_lower
            MediaViewerKind.VIDEO_NOTE -> R.string.media_kind_video_note
            MediaViewerKind.VOICE -> R.string.media_kind_voice
            MediaViewerKind.AUDIO -> R.string.media_kind_audio
            MediaViewerKind.UNKNOWN -> R.string.media_kind_unknown
        })
        val kindAndHdr = if (item.hdr == MediaHdr.None) kind else compose.activity.getString(
            R.string.media_viewer_kind_hdr, kind, compose.activity.getString(R.string.media_info_hdr),
        )
        return compose.activity.getString(
            R.string.media_viewer_page_item, position(index, count), kindAndHdr,
            item.senderName ?: compose.activity.getString(R.string.media_sender_unknown), item.dateLabel.orEmpty(),
        )
    }
}
