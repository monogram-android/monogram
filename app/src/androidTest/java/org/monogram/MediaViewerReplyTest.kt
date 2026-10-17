package org.monogram

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.monogram.core.ui.R
import org.monogram.core.ui.media.MediaAlbumState
import org.monogram.core.ui.media.MediaPlaybackHolder
import org.monogram.core.ui.media.MediaViewerActions
import org.monogram.core.ui.media.MediaViewerItem
import org.monogram.core.ui.media.MediaViewerKind
import org.monogram.core.ui.media.MediaViewerShell
import org.monogram.core.ui.media.MediaViewerTheme
import org.monogram.core.ui.theme.MonogramTheme

class MediaViewerReplyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After
    fun stopPlayback() {
        compose.runOnIdle { MediaPlaybackHolder.peek()?.stop() }
    }

    @Test
    fun replyMenuDispatchesNavigatedItemInsteadOfSelectedSubset() {
        val first = MediaViewerItem(id = "chat:7", kind = MediaViewerKind.PHOTO)
        val second = MediaViewerItem(id = "chat:8", kind = MediaViewerKind.PHOTO)
        val album = MediaAlbumState(listOf(first, second))
        album.toggleSelection(first.id)
        var replied: MediaViewerItem? = null
        launch(album, MediaViewerActions(onReply = { replied = it }))

        compose.onRoot().performTouchInput { swipe(centerRight, centerLeft, 220) }
        compose.waitUntil(5_000) { album.index == 1 }
        openMenu()
        compose.onNodeWithText(compose.activity.getString(R.string.media_action_reply))
            .assertExists().performClick()

        compose.runOnIdle {
            assertSame(second, replied)
            assertEquals(listOf(first), album.selectedItems())
        }
    }

    @Test
    fun defaultNullCallbackOmitsReplyMenuItem() {
        val album = MediaAlbumState(listOf(MediaViewerItem(id = "photo", kind = MediaViewerKind.PHOTO)))
        launch(album, MediaViewerActions())

        openMenu()

        compose.onNodeWithText(compose.activity.getString(R.string.media_action_reply)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.media_action_show_in_chat)).assertExists()
    }

    private fun openMenu() {
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.media_more_actions)).performClick()
    }

    private fun launch(album: MediaAlbumState, actions: MediaViewerActions) {
        compose.setContent {
            MonogramTheme {
                MediaViewerTheme {
                    val session = remember { MediaPlaybackHolder.session(compose.activity) }
                    MediaViewerShell(
                        album = album,
                        onDismiss = {},
                        actions = actions,
                        session = session,
                        chatKey = "reply-test",
                    )
                }
            }
        }
        compose.waitForIdle()
    }
}
