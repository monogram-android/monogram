package org.monogram

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Gainmap
import android.graphics.ImageDecoder
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.monogram.core.ui.theme.MonogramTheme
import java.io.File

class MediaViewerHdrTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var scenario: ActivityScenario<UiStateTestActivity>? = null
    private lateinit var activity: UiStateTestActivity
    private lateinit var album: MediaAlbumState
    private var visible by mutableStateOf(true)

    @After
    fun cleanUp() {
        scenario?.close()
        UiStateTestActivity.content = {}
    }

    @Test
    @SdkSuppress(minSdkVersion = 34)
    fun gainmapDecodeUpdatesAlbumAndBadgeAndSdrPageClearsHdrAndProtection() {
        val gainmap = gainmapJpeg()
        val sdr = sdrJpeg()
        launch(listOf(photo("gainmap", gainmap, protectedContent = true), photo("sdr", sdr)))
        awaitGainmap()
        assertViewerWindow(hdr = true, secure = true)

        compose.onNode(hasContentDescription(pageLabel(0)) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "100%")).performTouchInput { swipe(centerRight, centerLeft, 300) }
        compose.waitUntil(5_000) { album.index == 1 }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(MediaHdr.None, album.current?.hdr)
            assertEquals(MediaHdr.GainMap, album.items.first().hdr)
        }
        compose.onNodeWithText("HDR").assertDoesNotExist()
        assertViewerWindow(hdr = false, secure = false)

        compose.onNode(hasContentDescription(pageLabel(1)) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "100%")).performTouchInput { swipe(centerLeft, centerRight, 300) }
        compose.waitUntil(5_000) { album.index == 0 }
        awaitGainmap()
        assertViewerWindow(hdr = true, secure = true)
        dismiss()
        assertViewerWindow(hdr = false, secure = false)
    }

    @Test
    @SdkSuppress(minSdkVersion = 34)
    fun stoppingGainmapViewerRestoresSdrAndResumeReappliesDisplaySupportedMode() {
        launch(listOf(photo("gainmap", gainmapJpeg(), protectedContent = true)))
        awaitGainmap()
        assertViewerWindow(hdr = true, secure = true)

        scenario!!.moveToState(Lifecycle.State.CREATED)
        instrumentation.runOnMainSync {
            assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, activity.window.colorMode)
            assertTrue("protected media stays secure while stopped", isSecure())
        }
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        awaitGainmap()
        assertViewerWindow(hdr = true, secure = true)
        dismiss()
        assertViewerWindow(hdr = false, secure = false)
    }

    @Test
    fun ordinaryJpegStaysSdrAndDismissalClearsProtectedWindow() {
        launch(listOf(photo("sdr", sdrJpeg(), protectedContent = true)))
        compose.onNodeWithText("HDR").assertDoesNotExist()
        compose.runOnIdle { assertEquals(MediaHdr.None, album.current?.hdr) }
        assertViewerWindow(hdr = false, secure = true)
        dismiss()
        assertViewerWindow(hdr = false, secure = false)
    }

    @Test
    fun persistedGainmapJpegDetectsHdrOnApi34AndFallsBackToSdrOnOlderApis() {
        val file = File(instrumentation.targetContext.cacheDir, "viewer-ultra-hdr.jpg")
        instrumentation.context.assets.open("ultra-hdr.jpg").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val decoded = requireNotNull(BitmapFactory.decodeFile(file.absolutePath)) {
            "the same gainmap JPEG must decode on every supported API"
        }
        try {
            assertEquals(256, decoded.width)
            assertEquals(192, decoded.height)
            if (Build.VERSION.SDK_INT >= 34) assertTrue("persisted fixture must retain its gainmap", decoded.hasGainmap())
        } finally {
            decoded.recycle()
        }
        launch(listOf(photo("persisted-gainmap", file, protectedContent = true)))
        if (Build.VERSION.SDK_INT >= 34) {
            awaitGainmap()
        } else {
            compose.onNodeWithText("HDR").assertDoesNotExist()
            compose.runOnIdle { assertEquals(MediaHdr.None, album.current?.hdr) }
        }
        assertViewerWindow(hdr = Build.VERSION.SDK_INT >= 34, secure = true)
        dismiss()
        assertViewerWindow(hdr = false, secure = false)
    }

    private fun launch(items: List<MediaViewerItem>) {
        visible = true
        album = MediaAlbumState(items)
        assertTrue("HDR must be discovered from the decoded photo", items.all { it.hdr == MediaHdr.None })
        UiStateTestActivity.content = {
            MonogramTheme {
                if (visible) MediaViewerHost(album = album, onDismiss = { visible = false }, motion = false)
            }
        }
        scenario = ActivityScenario.launch(UiStateTestActivity::class.java)
        scenario!!.onActivity { activity = it }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(activity.getString(R.string.media_preview_close)).assertIsDisplayed()
    }

    private fun awaitGainmap() {
        compose.waitUntil(10_000) { album.current?.hdr == MediaHdr.GainMap }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("HDR")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("HDR").assertIsDisplayed()
    }

    private fun dismiss() {
        compose.onNodeWithContentDescription(activity.getString(R.string.media_preview_close)).performClick()
        compose.waitUntil(5_000) { !visible }
        compose.waitForIdle()
        compose.onNodeWithText("HDR").assertDoesNotExist()
    }

    private fun assertViewerWindow(hdr: Boolean, secure: Boolean) {
        compose.runOnIdle {
            if (Build.VERSION.SDK_INT >= 26) {
                val display = activity.window.decorView.display
                val hdrAvailable = Build.VERSION.SDK_INT >= 34 && display != null &&
                    display.isHdr && display.hdrSdrRatio > 1f
                val expected = if (hdr && hdrAvailable) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
                assertEquals("window mode must follow actual display HDR headroom", expected, activity.window.colorMode)
            }
            assertEquals("FLAG_SECURE must follow the current photo and clear on dismissal", secure, isSecure())
        }
    }

    private fun pageLabel(index: Int): String {
        val kind = activity.getString(R.string.media_badge_photo_lower)
        val kindLabel = if (album.items[index].hdr != MediaHdr.None) activity.getString(
            R.string.media_viewer_kind_hdr, kind, activity.getString(R.string.media_info_hdr),
        ) else kind
        return activity.getString(
            R.string.media_viewer_page_item,
            activity.getString(R.string.media_viewer_page_position, index + 1, album.count),
            kindLabel, "Fixture album", "",
        )
    }

    private fun isSecure(): Boolean = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    private fun photo(id: String, file: File, protectedContent: Boolean = false) = MediaViewerItem(
        id = id,
        kind = MediaViewerKind.PHOTO,
        source = MediaSource.Local(file),
        protectedContent = protectedContent,
        senderName = "Fixture album",
    )

    private fun sdrJpeg(): File {
        val file = File(instrumentation.targetContext.cacheDir, "viewer-sdr.jpg")
        val bitmap = Bitmap.createBitmap(256, 192, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(80, 120, 160))
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        } finally {
            bitmap.recycle()
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file))
            try {
                assertFalse("ordinary JPEG must not carry a gainmap", decoded.hasGainmap())
            } finally {
                decoded.recycle()
            }
        }
        return file
    }

    @androidx.annotation.RequiresApi(34)
    private fun gainmapJpeg(): File {
        // Keep the actual encoded JPEG available for export as a pre-34 fixture asset.
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "viewer-qa").apply { mkdirs() }
        val file = File(directory, "gainmap.jpg")
        val bitmap = Bitmap.createBitmap(256, 192, Bitmap.Config.ARGB_8888)
        val map = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(160, 120, 80))
            map.eraseColor(Color.rgb(192, 192, 192))
            bitmap.setGainmap(Gainmap(map).apply {
                setRatioMin(1f, 1f, 1f)
                setRatioMax(4f, 4f, 4f)
                setGamma(1f, 1f, 1f)
                setEpsilonSdr(0f, 0f, 0f)
                setEpsilonHdr(0f, 0f, 0f)
                setMinDisplayRatioForHdrTransition(1f)
                setDisplayRatioForFullHdr(4f)
            })
            assertTrue(bitmap.hasGainmap())
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        } finally {
            bitmap.recycle()
            map.recycle()
        }
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file))
        try {
            assertTrue("JPEG compression must preserve the gainmap, not merely its filename", decoded.hasGainmap())
            assertEquals(256, decoded.width)
            assertEquals(192, decoded.height)
        } finally {
            decoded.recycle()
        }
        instrumentation.uiAutomation.executeShellCommand("mkdir -p /sdcard/Download/monogram-viewer-qa").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        instrumentation.uiAutomation.executeShellCommand(
            "cp ${file.absolutePath} /sdcard/Download/monogram-viewer-qa/ultra-hdr.jpg",
        ).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        return file
    }
}
