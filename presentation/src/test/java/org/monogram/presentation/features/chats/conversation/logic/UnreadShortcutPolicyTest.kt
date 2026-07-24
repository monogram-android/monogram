package org.monogram.presentation.features.chats.conversation.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.features.chats.conversation.ChatComponent

class UnreadShortcutPolicyTest {
    @Test
    fun `latest loaded unread mention is preferred as shortcut target`() {
        val action = resolveUnreadShortcutAction(
            messages = listOf(
                message(id = 11L, hasUnreadMention = true),
                message(id = 12L, hasUnreadMention = false),
                message(id = 14L, hasUnreadMention = true)
            ),
            targetChatId = CHAT_ID,
            isLatestLoaded = true,
            isOldestLoaded = false,
            type = UnreadShortcutType.Mention
        )

        assertEquals(14L, (action as UnreadShortcutAction.ScrollToMessage).messageId)
    }

    @Test
    fun `missing unread shortcut target falls back to loading newer first`() {
        val action = resolveUnreadShortcutAction(
            messages = listOf(message(id = 11L), message(id = 12L)),
            targetChatId = CHAT_ID,
            isLatestLoaded = false,
            isOldestLoaded = false,
            type = UnreadShortcutType.Reaction
        )

        assertEquals(UnreadShortcutAction.LoadNewer, action)
    }

    @Test
    fun `missing unread shortcut target falls back to loading older after latest is loaded`() {
        val action = resolveUnreadShortcutAction(
            messages = listOf(message(id = 11L), message(id = 12L)),
            targetChatId = CHAT_ID,
            isLatestLoaded = true,
            isOldestLoaded = false,
            type = UnreadShortcutType.Mention
        )

        assertEquals(UnreadShortcutAction.LoadOlder, action)
    }

    @Test
    fun `clearing unread mentions resets count and loaded flags for active chat only`() {
        val state = ChatComponent.State(
            chatId = CHAT_ID,
            unreadMentionCount = 3,
            unreadReactionCount = 2,
            messages = listOf(
                message(id = 11L, hasUnreadMention = true),
                message(id = 12L, chatId = OTHER_CHAT_ID, hasUnreadMention = true),
                message(id = 13L, hasUnreadReactions = true)
            )
        )

        val cleared = state.clearUnreadShortcut(
            targetChatId = CHAT_ID,
            type = UnreadShortcutType.Mention
        )

        assertEquals(0, cleared.unreadMentionCount)
        assertEquals(2, cleared.unreadReactionCount)
        assertFalse(cleared.messages.first { it.id == 11L }.hasUnreadMention)
        assertTrue(cleared.messages.first { it.id == 12L }.hasUnreadMention)
        assertTrue(cleared.messages.first { it.id == 13L }.hasUnreadReactions)
    }

    @Test
    fun `clearing unread reactions also clears root message flag`() {
        val state = ChatComponent.State(
            chatId = CHAT_ID,
            unreadReactionCount = 1,
            rootMessage = message(id = 99L, hasUnreadReactions = true)
        )

        val cleared = state.clearUnreadShortcut(
            targetChatId = CHAT_ID,
            type = UnreadShortcutType.Reaction
        )

        assertEquals(0, cleared.unreadReactionCount)
        assertFalse(cleared.rootMessage?.hasUnreadReactions == true)
    }

    private fun message(
        id: Long,
        chatId: Long = CHAT_ID,
        hasUnreadMention: Boolean = false,
        hasUnreadReactions: Boolean = false
    ): MessageModel =
        MessageModel(
            id = id,
            date = id.toInt(),
            isOutgoing = false,
            senderName = "sender",
            chatId = chatId,
            content = MessageContent.Text("text"),
            hasUnreadMention = hasUnreadMention,
            hasUnreadReactions = hasUnreadReactions
        )

    private companion object {
        const val CHAT_ID = 1L
        const val OTHER_CHAT_ID = 2L
    }
}
