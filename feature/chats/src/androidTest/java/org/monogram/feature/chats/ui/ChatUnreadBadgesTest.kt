package org.monogram.feature.chats.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.monogram.core.models.Chat
import org.monogram.core.models.PeerId
import org.monogram.feature.chats.R

class ChatUnreadBadgesTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun readingSpecialCountersUpdatesTheRenderedRowWithoutMovingInboxCursor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val mentions = context.getString(R.string.chats_unread_mentions)
        val reactions = context.getString(R.string.chats_unread_reactions)
        var chat by mutableStateOf(Chat(
            id = PeerId(1), title = "Unread indicators", readInboxMaxId = 100,
            unreadMentionsCount = 2, unreadReactionsCount = 1,
        ))
        compose.setContent {
            MaterialTheme {
                ChatRow(
                    chat = chat, selected = false, savedMessages = false,
                    mediaRepository = null, showAvatar = false, showReadStatus = false,
                    texts = chatListTexts(), onClick = {}, onAvatarClick = null,
                )
            }
        }
        compose.onNodeWithContentDescription(mentions, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(reactions, useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { chat = chat.copy(unreadMentionsCount = 1, unreadReactionsCount = 0) }
        compose.onNodeWithContentDescription(mentions, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(reactions, useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { chat = chat.copy(unreadMentionsCount = 0) }
        compose.onNodeWithContentDescription(mentions, useUnmergedTree = true).assertDoesNotExist()
    }
}
