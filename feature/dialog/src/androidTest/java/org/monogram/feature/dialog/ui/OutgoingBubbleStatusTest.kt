package org.monogram.feature.dialog.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.monogram.core.ui.components.OutgoingStatusMark
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.dialog.R

class OutgoingBubbleStatusTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val message = Message(
        MessageId(PeerId(5), -1),
        null,
        "Pending message",
        10,
        outgoing = true,
        pending = true
    )

    @Test
    fun pendingMessageUsesBubbleClockWithoutComposerNotice() {
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                MessageBubble(
                    message, null, showSender = false, joinsMessageAbove = false,
                    joinsMessageBelow = false, mediaRepository = null
                )
            }
        }
        compose.onNodeWithText("Pending message").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(org.monogram.core.ui.R.string.status_outgoing_sending))
            .assertExists()
        compose.onNodeWithText(context.getString(R.string.dialog_outbox_ready)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.dialog_outbox_waiting))
            .assertDoesNotExist()
        composerUiProof("outbox-pending-bubble.png")
    }

    @Test
    fun acknowledgementDoesNotReplayTheBubbleEntrance() {
        var model by mutableStateOf(
            message.copy(
                randomId = 42,
                date = System.currentTimeMillis() / 1000
            )
        )
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                MessageBubble(
                    model, null, showSender = false, joinsMessageAbove = false,
                    joinsMessageBelow = false, mediaRepository = null
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val top = compose.onNodeWithText("Pending message").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { model = model.copy(id = MessageId(PeerId(5), 100), pending = false) }
        repeat(8) {
            compose.mainClock.advanceTimeBy(32)
            assertEquals(
                top,
                compose.onNodeWithText("Pending message").fetchSemanticsNode().boundsInRoot.top,
                0.5f
            )
        }
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun statusTransitionKeepsItsWidthAcrossSendingFailureAndRead() {
        var phase by mutableStateOf(0)
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                OutgoingStatusMark(
                    pending = phase == 0,
                    failed = phase == 1,
                    read = phase == 3,
                    color = androidx.compose.ui.graphics.Color.Black,
                    modifier = Modifier.testTag("status")
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val width = compose.onNodeWithTag("status").fetchSemanticsNode().boundsInRoot.width
        for (target in 1..3) {
            compose.runOnIdle { phase = target }
            repeat(10) {
                compose.mainClock.advanceTimeBy(32)
                assertEquals(
                    width,
                    compose.onNodeWithTag("status").fetchSemanticsNode().boundsInRoot.width,
                    0.5f
                )
            }
        }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription(context.getString(org.monogram.core.ui.R.string.status_outgoing_read))
            .assertExists()
    }

    @Test
    fun retryDetailsExpandAndCollapseThroughIntermediateFrames() {
        var failed by mutableStateOf(false)
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                MessageBubble(
                    message.copy(pending = !failed, failed = failed), null,
                    showSender = false, joinsMessageAbove = false, joinsMessageBelow = false,
                    mediaRepository = null, onRetrySend = if (failed) ({}) else null,
                    modifier = Modifier.testTag("bubble")
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        fun height() = compose.onNodeWithTag("bubble").fetchSemanticsNode().boundsInRoot.height
        val initial = height()
        compose.runOnIdle { failed = true }
        val expanding = List(10) { compose.mainClock.advanceTimeBy(32); height() }
        val expanded = expanding.last()
        org.junit.Assert.assertTrue(expanded > initial)
        org.junit.Assert.assertTrue(expanding.any { it > initial + 1 && it < expanded - 1 })
        compose.runOnIdle { failed = false }
        val collapsing = List(10) { compose.mainClock.advanceTimeBy(32); height() }
        org.junit.Assert.assertTrue(collapsing.any { it > initial + 1 && it < expanded - 1 })
        assertEquals(initial, collapsing.last(), 0.5f)
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun uncertainDeliveryShowsExplicitRetryOnBubble() {
        var retries = 0
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                MessageBubble(
                    message.copy(pending = false, failed = true), null, showSender = false,
                    joinsMessageAbove = false, joinsMessageBelow = false, mediaRepository = null,
                    sendUncertain = true, onRetrySend = { retries++ })
            }
        }
        compose.onNodeWithText(context.getString(R.string.dialog_outbox_uncertain))
            .assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, retries) }
        composerUiProof("outbox-uncertain-bubble.png")
        compose.onNodeWithText(context.getString(org.monogram.core.ui.R.string.status_retry))
            .performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
