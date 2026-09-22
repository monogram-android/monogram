package org.monogram

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.core.models.TextEntity
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.dialog.ui.MessageBubble

class MessageBubbleTapTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var menus = 0
    private var profiles = 0
    private var openedUri: String? = null

    @Test fun incomingTextAndEmptyRowSpaceOpenMenuOnce() = verifyTaps(outgoing = false)
    @Test fun outgoingTextAndEmptyRowSpaceOpenMenuOnce() = verifyTaps(outgoing = true)

    @Test
    fun senderTapKeepsItsProfileAction() {
        show(outgoing = false, showSender = true)
        compose.onNodeWithText("Alice").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, profiles)
            assertEquals(0, menus)
        }
    }

    @Test
    fun linkTapOpensLinkInsteadOfMessageMenu() {
        show(outgoing = false, linked = true)
        compose.onNodeWithText("Hello world").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals("https://example.com", openedUri)
            assertEquals(0, menus)
        }
    }

    @Test
    fun selectingTextDoesNotReopenMessageMenu() {
        show(outgoing = false, selectable = true)
        compose.onNodeWithText("Hello world").performTouchInput { longClick() }
        compose.onNodeWithTag("message-row").performTouchInput { click(Offset(width - 4f, center.y)) }
        compose.runOnIdle { assertEquals(0, menus) }
    }

    @Test
    fun commentsFooterOpensCommentsInsteadOfMenu() {
        var comments = 0
        show(outgoing = false, discussionPeerId = 1L, onComments = { comments++ })
        compose.onNodeWithTag("message-comments").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, comments)
            assertEquals(0, menus)
        }
    }

    @Test
    fun commentsFooterShowsCount() {
        show(outgoing = false, discussionPeerId = 1L, repliesCount = 1, onComments = {})
        val label = compose.activity.resources.getQuantityString(
            org.monogram.feature.dialog.R.plurals.dialog_comments_count,
            1,
            1,
        )
        compose.onNodeWithText(label).assertIsDisplayed()
    }

    private fun verifyTaps(outgoing: Boolean) {
        show(outgoing)
        compose.onNodeWithText("Hello world").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, menus) }
        compose.onNodeWithTag("message-row").performTouchInput {
            click(Offset(if (outgoing) 4f else width - 4f, center.y))
        }
        compose.runOnIdle { assertEquals(2, menus) }
        compose.onNodeWithText("Hello world").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(3, menus) }
    }

    private fun show(
        outgoing: Boolean,
        showSender: Boolean = false,
        selectable: Boolean = false,
        linked: Boolean = false,
        discussionPeerId: Long? = null,
        repliesCount: Int = 0,
        onComments: (() -> Unit)? = null,
    ) {
        compose.setContent {
            MonogramTheme {
                CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) { openedUri = uri }
                }) {
                Box(Modifier.fillMaxSize()) {
                    MessageBubble(
                        message = Message(
                            id = MessageId(PeerId(1), 1), senderId = PeerId(2),
                            text = "Hello world", date = 1L, outgoing = outgoing,
                            entities = if (linked) listOf(TextEntity("text_url", 0, 11, "https://example.com")) else emptyList(),
                            repliesCount = repliesCount,
                            discussionPeerId = discussionPeerId,
                        ),
                        sender = Profile(PeerId(2), "user", "Alice"),
                        showSender = showSender,
                        joinsMessageAbove = false,
                        joinsMessageBelow = false,
                        mediaRepository = null,
                        onOpenSender = { profiles++ },
                        onOpenMenu = { menus++ },
                        onComments = onComments,
                        selectable = selectable,
                        modifier = Modifier.testTag("message-row"),
                    )
                }
                }
            }
        }
    }
}
