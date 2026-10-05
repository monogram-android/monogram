@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package org.monogram

import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.monogram.core.ui.R
import org.monogram.core.ui.media.*
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.dialog.ui.MediaShareActions
import java.io.File

class MediaViewerSaveAsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After
    fun stopPlayback() {
        compose.runOnIdle { MediaPlaybackHolder.peek()?.stop() }
    }

    @Test
    fun createDocumentUsesOriginalMimeAndSafeName() {
        val intent = MediaShareActions.createDocumentIntent("image/jpeg", "gain?map.jpg")
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("image/jpeg", intent.type)
        assertEquals("gain_map.jpg", intent.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test
    fun documentCopyPreservesUltraHdrOriginalBytes() = runBlocking {
        val context = compose.activity
        val source = File(context.cacheDir, "save-as-source.jpg")
        val destination = File(context.cacheDir, "save-as-destination.jpg")
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("ultra-hdr.jpg").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            assertTrue(MediaShareActions.saveToDocument(context, source, Uri.fromFile(destination)))
            assertArrayEquals(source.readBytes(), destination.readBytes())
            if (Build.VERSION.SDK_INT >= 34) {
                val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(destination))
                try { assertTrue(bitmap.hasGainmap()) } finally { bitmap.recycle() }
            }
        } finally {
            source.delete()
            destination.delete()
        }
    }

    @Test
    fun failedDocumentCopyReportsFailure() = runBlocking {
        assertFalse(MediaShareActions.saveToDocument(
            compose.activity,
            File(compose.activity.cacheDir, "missing-save-as-source"),
            Uri.parse("content://missing.monogram.provider/file"),
        ))
    }

    @Test
    fun saveAsDispatchesCurrentItem() {
        val item = MediaViewerItem("current", MediaViewerKind.PHOTO)
        var saved: MediaViewerItem? = null
        launch(item, MediaViewerActions(onSaveAs = { saved = it }))
        openMenu()
        compose.onNodeWithText(compose.activity.getString(R.string.media_action_save_as)).performClick()
        compose.runOnIdle { assertSame(item, saved) }
    }

    @Test
    fun protectedItemOmitsSaveAs() {
        launch(MediaViewerItem("protected", MediaViewerKind.PHOTO, protectedContent = true),
            MediaViewerActions(onSaveAs = { fail("Protected save-as callback must not run") }))
        openMenu()
        compose.onNodeWithText(compose.activity.getString(R.string.media_action_save_as)).assertDoesNotExist()
    }

    @Test
    fun protectedCaptionSheetOmitsCopyAction() {
        launch(MediaViewerItem("protected-caption", MediaViewerKind.PHOTO,
            caption = "Protected caption", protectedContent = true), MediaViewerActions())
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.media_caption_expand)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.media_action_copy_caption)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.media_action_show_in_chat)).assertExists()
    }

    private fun openMenu() {
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.media_more_actions)).performClick()
    }

    private fun launch(item: MediaViewerItem, actions: MediaViewerActions) {
        compose.setContent {
            MonogramTheme {
                MediaViewerTheme {
                    val session = remember { MediaPlaybackHolder.session(compose.activity) }
                    MediaViewerShell(
                        album = remember { MediaAlbumState(listOf(item)) },
                        onDismiss = {}, actions = actions, session = session, chatKey = "save-as-test",
                    )
                }
            }
        }
        compose.waitForIdle()
    }
}
