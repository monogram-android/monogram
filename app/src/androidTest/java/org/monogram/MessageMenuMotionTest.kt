package org.monogram

import androidx.activity.ComponentActivity
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.MessageViewer
import org.monogram.core.models.MessageViewers
import org.monogram.core.models.PeerId
import org.monogram.core.ui.menu.AppMenuPlacementState
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.dialog.ui.MessageActionMenu
import org.monogram.feature.dialog.ui.MessageMenuActions
import org.monogram.feature.dialog.ui.MessageSeenByRow
import org.monogram.feature.dialog.ui.rememberMessageMenuPosition
import java.io.File

class MessageMenuMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun phoneViewerPanelExpandsAndReturnsSmoothly() = exerciseMenu(tablet = false)
    @Test fun tabletDarkViewerPanelExpandsAndReturnsSmoothly() = exerciseMenu(tablet = true)

    private fun exerciseMenu(tablet: Boolean) {
        val viewers = mutableStateOf<MessageViewers>(MessageViewers.Loading)
        val shown = mutableStateOf(true)
        var popupCreations = 0
        val message = Message(MessageId(PeerId(1), 1), senderId = null, text = "Menu preview", date = 1L, outgoing = true)
        compose.setContent {
            val density = if (tablet) Density(1f, fontScale = 1.3f) else LocalDensity.current
            MonogramTheme(darkTheme = tablet) {
                CompositionLocalProvider(LocalDensity provides density) {
                    Box(Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background)) {
                        val placement = remember { AppMenuPlacementState() }
                        val visibility = remember { MutableTransitionState(false) }
                        visibility.targetState = shown.value
                        val metrics = compose.activity.resources.displayMetrics
                        if (!visibility.isIdle || visibility.currentState || visibility.targetState) {
                        DisposableEffect(Unit) {
                            popupCreations++
                            onDispose {}
                        }
                        Popup(
                            popupPositionProvider = rememberMessageMenuPosition(
                                outgoing = true,
                                touch = Offset(metrics.widthPixels * 0.85f, metrics.heightPixels * 0.75f),
                                placementState = placement,
                            ),
                            properties = PopupProperties(focusable = true, clippingEnabled = false),
                        ) {
                            CompositionLocalProvider(LocalDensity provides density) {
                                MessageActionMenu(
                                    expanded = shown.value, message = message,
                                    visibilityState = visibility,
                                    actions = MessageMenuActions(true, true, true, true, true, false),
                                    onDismiss = {}, onReply = {}, onCopy = {}, onEdit = {}, onDelete = {}, onForward = {},
                                    outgoing = true, growth = placement.growth,
                                    seenByRow = {
                                        MessageSeenByRow(viewers.value, { null }, {}, Modifier.testTag("viewers"))
                                    },
                                )
                            }
                        }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            viewers.value = MessageViewers.Ready(
                listOf("Alice", "Александр с длинным именем", "Charlie").mapIndexed { index, title ->
                    MessageViewer(PeerId(index + 2L), 1_800_000_000L, title)
                },
                played = false,
            )
        }
        val summary = compose.activity.getString(org.monogram.feature.dialog.R.string.dialog_seen_by_count, 3)
        compose.onNodeWithText(summary).assertIsDisplayed()
        val smallHeight = compose.onNodeWithTag("viewers").getBoundsInRoot().let { (it.bottom - it.top).value }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(summary).performClick()
        compose.mainClock.advanceTimeBy(48)
        val earlyHeight = compose.onNodeWithTag("viewers").getBoundsInRoot().let { (it.bottom - it.top).value }
        capture(if (tablet) "tablet-expanding" else "phone-expanding")
        compose.mainClock.advanceTimeBy(300)
        val fullHeight = compose.onNodeWithTag("viewers").getBoundsInRoot().let { (it.bottom - it.top).value }
        assertTrue("Viewer panel must resize over multiple frames", earlyHeight > smallHeight && earlyHeight < fullHeight)
        compose.onNodeWithText("Alice").assertIsDisplayed()
        capture(if (tablet) "tablet-expanded" else "phone-expanded")
        compose.onNodeWithText(compose.activity.getString(org.monogram.feature.dialog.R.string.dialog_seen_list_back)).performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText(summary).assertIsDisplayed()
        assertTrue(kotlin.math.abs(compose.onNodeWithTag("viewers").getBoundsInRoot().let { (it.bottom - it.top).value } - smallHeight) < 2f)
        val copyLabel = compose.activity.getString(org.monogram.feature.dialog.R.string.dialog_copy)
        val beforeClosing = compose.onNodeWithText(copyLabel).getBoundsInRoot()
        compose.runOnIdle { shown.value = false }
        compose.mainClock.advanceTimeBy(64)
        val duringClosing = compose.onNodeWithText(copyLabel).getBoundsInRoot()
        assertTrue("Closing menu must not drift horizontally", kotlin.math.abs(beforeClosing.left.value - duringClosing.left.value) < 1f)
        assertTrue("Closing menu must not drift vertically", kotlin.math.abs(beforeClosing.top.value - duringClosing.top.value) < 1f)
        capture(if (tablet) "tablet-closing" else "phone-closing")
        compose.mainClock.advanceTimeBy(160)
        compose.onNodeWithText(copyLabel).assertDoesNotExist()
        compose.onNode(isPopup()).assertDoesNotExist()
        capture(if (tablet) "tablet-closed" else "phone-closed")
        val creationsBeforeRapidTaps = popupCreations
        repeat(8) {
            compose.runOnIdle { shown.value = true }
            compose.mainClock.advanceTimeBy(32)
            compose.runOnIdle { shown.value = false }
            compose.mainClock.advanceTimeBy(16)
        }
        compose.runOnIdle { shown.value = true }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText(copyLabel).assertIsDisplayed()
        compose.onNode(isPopup()).assertExists()
        assertTrue("Interrupted transitions must retain the same popup", popupCreations == creationsBeforeRapidTaps + 1)
        compose.runOnIdle { shown.value = false }
        compose.mainClock.advanceTimeBy(300)
        compose.onNode(isPopup()).assertDoesNotExist()
        capture(if (tablet) "tablet-rapid-closed" else "phone-rapid-closed")
        compose.mainClock.autoAdvance = true
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot().apply {
            File(instrumentation.targetContext.getExternalFilesDir(null), "menu-$name.png").outputStream().use {
                compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            recycle()
        }
    }
}
