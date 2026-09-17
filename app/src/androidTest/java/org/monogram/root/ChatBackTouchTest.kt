package org.monogram.root

import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.LayoutDirection
import com.arkivanov.essenty.backhandler.BackCallback
import com.arkivanov.essenty.backhandler.BackDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatBackTouchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var backs = 0
    private var childDrags = 0

    @Test
    fun settingsHorizontalControlKeepsItsGesture() {
        show(verticalChild = false, prioritizeChildren = true)
        compose.onNodeWithTag("content").performTouchInput {
            swipe(Offset(width * 0.25f, center.y), Offset(width * 0.8f, center.y), 600)
        }
        compose.runOnIdle {
            assertEquals(0, backs)
            assertTrue(childDrags > 0)
        }
    }

    @Test
    fun swipeAcrossMessageAwayFromEdgeClosesChat() {
        show(verticalChild = false)
        compose.onNodeWithTag("content").performTouchInput {
            swipe(Offset(width * 0.35f, center.y), Offset(width * 0.85f, center.y), 600)
        }
        compose.runOnIdle {
            assertEquals(1, backs)
            assertEquals(0, childDrags)
        }
    }

    @Test
    fun oppositeSwipeRemainsAvailableToMessageReply() {
        show(verticalChild = false)
        compose.onNodeWithTag("content").performTouchInput {
            swipe(Offset(width * 0.75f, center.y), Offset(width * 0.25f, center.y), 600)
        }
        compose.runOnIdle {
            assertEquals(0, backs)
            assertTrue(childDrags > 0)
        }
    }

    @Test
    fun firstEdgeSwipeWinsOverChildHorizontalDrag() {
        show(verticalChild = false)
        compose.onNodeWithTag("content").performTouchInput {
            val start = Offset(40 * density, center.y)
            swipe(start, start + Offset(width * 0.55f, 0f), 600)
        }
        compose.runOnIdle {
            assertEquals(1, backs)
            assertEquals(0, childDrags)
        }
    }

    @Test
    fun quickShortEdgeSwipeCompletes() {
        show(verticalChild = false)
        compose.onNodeWithTag("content").performTouchInput {
            val start = Offset(40 * density, center.y)
            swipe(start, start + Offset(80 * density, 0f), 80)
        }
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test
    fun verticalSwipeStillScrollsChild() {
        show(verticalChild = true)
        compose.onNodeWithTag("content").performTouchInput {
            val start = Offset(40 * density, height * 0.3f)
            swipe(start, start + Offset(0f, height * 0.3f), 600)
        }
        compose.runOnIdle {
            assertEquals(0, backs)
            assertTrue(childDrags > 0)
        }
    }

    private val density get() = compose.activity.resources.displayMetrics.density

    private fun show(verticalChild: Boolean, prioritizeChildren: Boolean = false) {
        val dispatcher = BackDispatcher().apply { register(BackCallback(onBack = { backs++ })) }
        compose.setContent {
            Box(Modifier.fillMaxSize().chatBackGesture(dispatcher, LayoutDirection.Ltr, { prioritizeChildren }) { true }) {
                Box(Modifier.fillMaxSize().testTag("content").pointerInput(verticalChild) {
                    if (verticalChild) detectVerticalDragGestures { change, _ -> change.consume(); childDrags++ }
                    else detectHorizontalDragGestures { change, _ -> change.consume(); childDrags++ }
                })
            }
        }
    }
}
