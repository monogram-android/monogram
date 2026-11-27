package org.monogram.feature.dialog.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.core.ui.components.OutgoingStatusMark
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.dialog.R

class OutgoingBubbleStatusTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val message =
        Message(
            MessageId(PeerId(5), -1),
            null,
            "Pending message",
            10,
            outgoing = true,
            pending = true,
        )

    @Test
    fun pendingMessageUsesBubbleClockWithoutComposerNotice() {
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                MessageBubble(
                    message,
                    null,
                    showSender = false,
                    joinsMessageAbove = false,
                    joinsMessageBelow = false,
                    mediaRepository = null,
                )
            }
        }
        compose.onNodeWithText("Pending message").assertIsDisplayed()
        compose
            .onNodeWithContentDescription(context.getString(org.monogram.core.ui.R.string.status_outgoing_sending))
            .assertExists()
        compose.onNodeWithText(context.getString(R.string.dialog_outbox_ready)).assertDoesNotExist()
        compose
            .onNodeWithText(context.getString(R.string.dialog_outbox_waiting))
            .assertDoesNotExist()
        composerUiProof("outbox-pending-bubble.png")
    }

    @Test
    fun stackedAndInlineTimestampsKeepOutgoingBubblesAtTheEnd() {
        val longText = "иди посиди в твиттере подольше"
        val shortText = "новорег"
        var time = ""
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                time = messageTime(message.date)
                Column(Modifier.width(360.dp)) {
                    listOf(longText, shortText).forEachIndexed { index, text ->
                        MessageBubble(
                            message.copy(text = text),
                            null,
                            showSender = false,
                            joinsMessageAbove = false,
                            joinsMessageBelow = false,
                            mediaRepository = null,
                            modifier = Modifier.testTag("row-$index"),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        fun timestamp(index: Int) =
            compose
                .onNode(
                    hasText(time) and hasAnyAncestor(hasTestTag("row-$index")),
                    useUnmergedTree = true,
                ).fetchSemanticsNode()
                .boundsInRoot

        val longTimestamp = timestamp(0)
        val shortTimestamp = timestamp(1)

        fun body(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
        assertTrue("long timestamp is stacked", longTimestamp.top >= body(longText).bottom)
        assertTrue("short timestamp is inline", shortTimestamp.top < body(shortText).bottom)

        val rowRight =
            compose
                .onNodeWithTag("row-1")
                .fetchSemanticsNode()
                .boundsInRoot.right
        val padding = with(compose.density) { BUBBLE_CONTENT_PAD.roundToPx() }
        assertEquals("short bubble end", rowRight, shortTimestamp.right + padding, 1f)
        assertEquals("long bubble end", rowRight, longTimestamp.right + padding, 1f)
    }

    @Test
    fun acknowledgementDoesNotReplayTheBubbleEntrance() {
        var model by mutableStateOf(
            message.copy(
                randomId = 42,
                date = System.currentTimeMillis() / 1000,
            ),
        )
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                MessageBubble(
                    model,
                    null,
                    showSender = false,
                    joinsMessageAbove = false,
                    joinsMessageBelow = false,
                    mediaRepository = null,
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val top =
            compose
                .onNodeWithText("Pending message")
                .fetchSemanticsNode()
                .boundsInRoot.top
        compose.runOnIdle { model = model.copy(id = MessageId(PeerId(5), 100), pending = false) }
        repeat(8) {
            compose.mainClock.advanceTimeBy(32)
            assertEquals(
                top,
                compose
                    .onNodeWithText("Pending message")
                    .fetchSemanticsNode()
                    .boundsInRoot.top,
                0.5f,
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
                    modifier = Modifier.testTag("status"),
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val width =
            compose
                .onNodeWithTag("status")
                .fetchSemanticsNode()
                .boundsInRoot.width
        for (target in 1..3) {
            compose.runOnIdle { phase = target }
            repeat(10) {
                compose.mainClock.advanceTimeBy(32)
                assertEquals(
                    width,
                    compose
                        .onNodeWithTag("status")
                        .fetchSemanticsNode()
                        .boundsInRoot.width,
                    0.5f,
                )
            }
        }
        compose.mainClock.autoAdvance = true
        compose
            .onNodeWithContentDescription(context.getString(org.monogram.core.ui.R.string.status_outgoing_read))
            .assertExists()
    }

    @Test
    fun retryDetailsExpandAndCollapseThroughIntermediateFrames() {
        var failed by mutableStateOf(false)
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                MessageBubble(
                    message.copy(pending = !failed, failed = failed),
                    null,
                    showSender = false,
                    joinsMessageAbove = false,
                    joinsMessageBelow = false,
                    mediaRepository = null,
                    onRetrySend = if (failed) ({}) else null,
                    modifier = Modifier.testTag("bubble"),
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        fun height() =
            compose
                .onNodeWithTag("bubble")
                .fetchSemanticsNode()
                .boundsInRoot.height
        val initial = height()
        compose.runOnIdle { failed = true }
        val expanding =
            List(10) {
                compose.mainClock.advanceTimeBy(32)
                height()
            }
        val expanded = expanding.last()
        org.junit.Assert.assertTrue(expanded > initial)
        org.junit.Assert.assertTrue(expanding.any { it > initial + 1 && it < expanded - 1 })
        compose.runOnIdle { failed = false }
        val collapsing =
            List(10) {
                compose.mainClock.advanceTimeBy(32)
                height()
            }
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
                    message.copy(pending = false, failed = true),
                    null,
                    showSender = false,
                    joinsMessageAbove = false,
                    joinsMessageBelow = false,
                    mediaRepository = null,
                    sendUncertain = true,
                    onRetrySend = { retries++ },
                )
            }
        }
        compose
            .onNodeWithText(context.getString(R.string.dialog_outbox_uncertain))
            .assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, retries) }
        composerUiProof("outbox-uncertain-bubble.png")
        compose
            .onNodeWithText(context.getString(org.monogram.core.ui.R.string.status_retry))
            .performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
