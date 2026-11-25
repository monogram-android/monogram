package org.monogram.feature.dialog.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.dialog.R

class ForwardedBubbleLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun attributionAndReplyUseBubbleStartInLtr() = verifyLayout(LayoutDirection.Ltr)

    @Test
    fun attributionAndReplyUseBubbleStartInRtl() = verifyLayout(LayoutDirection.Rtl)

    private fun verifyLayout(direction: LayoutDirection) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = "Test source"
        val attribution = context.getString(R.string.dialog_forwarded_from, source)
        val sender = "Sender"
        val bot = "test_bot"
        val viaBot = context.getString(R.string.dialog_via_bot, bot)
        val botRole = context.getString(R.string.dialog_bot)
        val quote = "A short reply preview"
        val text =
            "A forwarded message with enough text to wrap across multiple lines in the bubble."
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    Column(Modifier.width(360.dp)) {
                        for (outgoing in listOf(false, true)) {
                            MessageBubble(
                                Message(
                                    MessageId(PeerId(5), 1),
                                    null,
                                    text,
                                    10,
                                    outgoing = outgoing,
                                    senderName = sender,
                                    fwdFrom = source,
                                    viaBot = "@$bot",
                                    replyQuote = quote,
                                ),
                                null,
                                senderTag = "role:bot",
                                showSender = true,
                                joinsMessageAbove = false,
                                joinsMessageBelow = false,
                                mediaRepository = null,
                                modifier = Modifier.testTag("row-$outgoing"),
                            )
                        }
                    }
                }
            }
        }

        val padding = with(compose.density) { BUBBLE_CONTENT_PAD.roundToPx() }
        val rtl = direction == LayoutDirection.Rtl
        for (outgoing in listOf(true, false)) {
            val rowTag = "row-$outgoing"
            val row = compose.onNodeWithTag(rowTag).fetchSemanticsNode().boundsInRoot

            fun bounds(label: String) =
                compose
                    .onNode(
                        hasText(label) and hasAnyAncestor(hasTestTag(rowTag)),
                        useUnmergedTree = true
                    )
                    .fetchSemanticsNode()
                    .boundsInRoot

            val body = bounds(text)
            val contentStart = if (rtl) body.right else body.left
            for (label in listOf(attribution, sender, viaBot)) {
                val header = bounds(label)
                assertEquals(
                    "$label start, outgoing=$outgoing",
                    contentStart,
                    if (rtl) header.right else header.left,
                    1f
                )
                assertTrue("$label wraps to its own width", header.width < body.width)
            }

            val senderBounds = bounds(sender)
            val role = bounds(botRole)
            val roleGap = with(compose.density) { (4.dp.roundToPx() + 6.dp.roundToPx()).toFloat() }
            assertEquals(
                "bot role follows sender",
                roleGap,
                if (rtl) senderBounds.left - role.right else role.left - senderBounds.right,
                1f,
            )

            val reply = bounds(quote)
            val replyInset = with(compose.density) { 3.dp.roundToPx() + 8.dp.roundToPx() }
            assertEquals(
                "reply preview start",
                contentStart + if (rtl) -replyInset else replyInset,
                if (rtl) reply.right else reply.left,
                1f,
            )

            val onRight = outgoing != rtl
            val outerEdge = if (onRight) body.right + padding else body.left - padding
            assertEquals(
                "bubble side, outgoing=$outgoing",
                if (onRight) row.right else row.left,
                outerEdge,
                1f
            )
            assertTrue("bubble does not fill the row", body.width + 2 * padding < row.width)
        }
    }
}
