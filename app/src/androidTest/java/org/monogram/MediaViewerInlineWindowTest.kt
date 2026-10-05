package org.monogram

import android.content.pm.ActivityInfo
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.monogram.core.ui.R
import org.monogram.core.ui.media.MediaAlbumState
import org.monogram.core.ui.media.MediaHdr
import org.monogram.core.ui.media.MediaSource
import org.monogram.core.ui.media.MediaViewerHost
import org.monogram.core.ui.media.MediaViewerItem
import org.monogram.core.ui.media.MediaViewerKind
import org.monogram.core.ui.media.MediaViewerOverlay
import org.monogram.core.ui.media.MediaViewerSharedRoot
import org.monogram.core.ui.media.rememberAlbumState
import org.monogram.core.ui.theme.MonogramTheme
import java.io.File

class MediaViewerInlineWindowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var scenario: ActivityScenario<UiStateTestActivity>? = null
    private lateinit var activity: UiStateTestActivity
    private lateinit var album: MediaAlbumState

    @After
    fun cleanup() {
        scenario?.close()
        UiStateTestActivity.content = {}
    }

    @Test
    fun inlineGainmapProtectionAndHdrSurviveStopResumeRecreationThenRestoreOnDismiss() {
        val file = File(instrumentation.targetContext.cacheDir, "inline-ultra-hdr.jpg")
        instrumentation.context.assets.open("ultra-hdr.jpg").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        UiStateTestActivity.content = {
            MonogramTheme {
                MediaViewerSharedRoot {
                    var visible by rememberSaveable { mutableStateOf(true) }
                    if (visible) MediaViewerOverlay {
                        album = rememberAlbumState(listOf(MediaViewerItem(
                            id = "inline-hdr",
                            kind = MediaViewerKind.PHOTO,
                            source = MediaSource.Local(file),
                            protectedContent = true,
                        )))
                        MediaViewerHost(album = album, onDismiss = { visible = false }, motion = false)
                    }
                }
            }
        }
        scenario = ActivityScenario.launch(UiStateTestActivity::class.java)
        scenario!!.onActivity { activity = it }
        awaitDecoded()
        assertWindow(started = true, secure = true)

        scenario!!.moveToState(Lifecycle.State.CREATED)
        instrumentation.runOnMainSync {
            if (Build.VERSION.SDK_INT >= 26) assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, activity.window.colorMode)
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        awaitDecoded()
        assertWindow(started = true, secure = true)

        scenario!!.recreate()
        scenario!!.onActivity { activity = it }
        awaitDecoded()
        assertWindow(started = true, secure = true)
        compose.onNodeWithContentDescription(activity.getString(R.string.media_preview_close)).performClick()
        compose.waitForIdle()
        assertWindow(started = false, secure = false)
    }

    private fun awaitDecoded() {
        compose.waitUntil(5_000) {
            val item = if (::album.isInitialized) album.current else null
            item?.width == 256 && item.height == 192 &&
                item.hdr == if (Build.VERSION.SDK_INT >= 34) MediaHdr.GainMap else MediaHdr.None
        }
        compose.waitForIdle()
        if (Build.VERSION.SDK_INT >= 34) compose.onNodeWithText("HDR").assertExists()
        else compose.onNodeWithText("HDR").assertDoesNotExist()
    }

    private fun assertWindow(started: Boolean, secure: Boolean) {
        instrumentation.runOnMainSync {
            assertEquals(secure, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            if (Build.VERSION.SDK_INT >= 26) {
                val display = activity.window.decorView.display
                val supportsHdr = Build.VERSION.SDK_INT >= 34 && display != null && display.isHdr && display.hdrSdrRatio > 1f
                val expected = if (started && supportsHdr) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
                assertEquals(expected, activity.window.colorMode)
            }
        }
    }
}
