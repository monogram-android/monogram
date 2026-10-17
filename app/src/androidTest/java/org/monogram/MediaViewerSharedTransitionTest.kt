package org.monogram

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.monogram.core.ui.media.LocalMediaViewerMotion
import org.monogram.core.ui.media.LocalMediaViewerSharedState
import org.monogram.core.ui.media.MediaAlbumState
import org.monogram.core.ui.media.MediaViewerHost
import org.monogram.core.ui.media.MediaViewerItem
import org.monogram.core.ui.media.MediaViewerKind
import org.monogram.core.ui.media.MediaViewerOverlay
import org.monogram.core.ui.media.MediaViewerSharedRoot
import org.monogram.core.ui.media.mediaMosaicSharedBounds
import org.monogram.core.ui.theme.MonogramTheme

class MediaViewerSharedTransitionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var visible by mutableStateOf(false)
    private var active = false
    private var dismissCount = 0
    private var sourceBounds = Rect.Zero
    private var siblingBounds = Rect.Zero
    private val key = "42:101"

    private fun launch(shared: Boolean = true, motion: Boolean = true, protected: Boolean = false) {
        compose.setContent {
            MonogramTheme {
                CompositionLocalProvider(LocalMediaViewerMotion provides motion) {
                    if (shared) MediaViewerSharedRoot { Content(motion, protected) }
                    else Content(motion, protected)
                }
            }
        }
    }

    @Composable
    private fun Content(motion: Boolean, protected: Boolean) {
        val root = LocalMediaViewerSharedState.current
        val running = root?.scope?.isTransitionActive == true
        SideEffect { active = running }
        Box(Modifier.fillMaxSize()) {
            Row {
                Box(
                    Modifier.size(80.dp)
                        .mediaMosaicSharedBounds(key)
                        .onGloballyPositioned { sourceBounds = it.boundsInRoot() }
                        .background(MaterialTheme.colorScheme.primary)
                        .testTag("source")
                        .clickable { visible = true },
                )
                Box(
                    Modifier.size(80.dp)
                        .mediaMosaicSharedBounds("42:102")
                        .onGloballyPositioned { siblingBounds = it.boundsInRoot() }
                        .background(MaterialTheme.colorScheme.secondary)
                        .testTag("sibling"),
                )
            }
            if (visible) {
                MediaViewerOverlay {
                    val album = remember {
                        MediaAlbumState(listOf(MediaViewerItem(
                            id = key,
                            kind = MediaViewerKind.PHOTO,
                            protectedContent = protected,
                        )), 0)
                    }
                    MediaViewerHost(
                        album = album,
                        onDismiss = { dismissCount++; visible = false },
                        motion = motion,
                        mediaLabel = "Shared photo",
                    )
                }
            }
        }
    }

    @Test
    fun matchingCellMorphsAndDismissWaitsForExitWhileSiblingStaysMeasured() {
        launch()
        compose.waitForIdle()
        val initialSource = sourceBounds
        val initialSibling = siblingBounds
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("source").performClick()
        compose.mainClock.advanceTimeBy(96)
        compose.runOnUiThread {
            assertTrue("Same-layout matching key must start a real shared transition", active)
            assertTrue("Shared cell must interpolate beyond its original bounds", sourceBounds.width > initialSource.width)
            assertEquals(initialSibling, siblingBounds)
        }
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithContentDescription("Shared photo").assertExists()
        compose.onNodeWithTag("source").assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(org.monogram.core.ui.R.string.media_preview_close)).performClick()
        compose.mainClock.advanceTimeBy(48)
        compose.runOnUiThread {
            assertTrue(visible)
            assertEquals(0, dismissCount)
            assertTrue(active)
        }
        compose.mainClock.advanceTimeBy(3_000)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertFalse(visible)
        assertEquals(1, dismissCount)
        compose.onNodeWithTag("source").assertExists()
        assertEquals(initialSource, sourceBounds)
        assertEquals(initialSibling, siblingBounds)
    }

    @Test
    fun absentSharedScopeUsesDialogFadeScaleAndDismissesOnce() {
        launch(shared = false)
        compose.onNodeWithTag("source").performClick()
        compose.onNodeWithContentDescription("Shared photo").assertExists()
        assertFalse(active)
        compose.onNodeWithContentDescription(compose.activity.getString(org.monogram.core.ui.R.string.media_preview_close)).performClick()
        compose.waitForIdle()
        assertFalse(visible)
        assertEquals(1, dismissCount)
    }

    @Test
    fun shortCommittedBackClosesInlineViewerOnceAfterCancellation() {
        launch()
        compose.onNodeWithTag("source").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            val dispatcher = compose.activity.onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(androidx.activity.BackEventCompat(0f, 0f, 0f, 0))
            dispatcher.dispatchOnBackProgressed(androidx.activity.BackEventCompat(0f, 0f, 0.6f, 0))
        }
        compose.waitForIdle()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        assertTrue(visible)
        assertEquals(0, dismissCount)
        compose.onNodeWithContentDescription("Shared photo").assertExists()
        compose.runOnIdle {
            val dispatcher = compose.activity.onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(androidx.activity.BackEventCompat(0f, 0f, 0f, 1))
            dispatcher.dispatchOnBackProgressed(androidx.activity.BackEventCompat(0f, 0f, 0.1f, 1))
        }
        compose.waitForIdle()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5_000) { !visible }
        assertEquals(1, dismissCount)
        compose.onNodeWithTag("source").assertExists()
    }

    @Test
    fun systemBackClosesDialogFallbackOnce() {
        launch(shared = false)
        compose.onNodeWithTag("source").performClick()
        compose.onNodeWithContentDescription("Shared photo").assertExists()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { !visible }
        assertEquals(1, dismissCount)
        compose.onNodeWithTag("source").assertExists()
    }

    @Test
    fun reducedMotionSnapsMatchedBoundsAndSecureWindowRestoresOnDismiss() {
        val wasSecure = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        launch(motion = false, protected = true)
        compose.onNodeWithTag("source").performClick()
        compose.waitForIdle()
        assertFalse(active)
        assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        compose.onNodeWithContentDescription(compose.activity.getString(org.monogram.core.ui.R.string.media_preview_close)).performClick()
        compose.waitForIdle()
        assertFalse(visible)
        assertEquals(1, dismissCount)
        assertEquals(wasSecure, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }
}
