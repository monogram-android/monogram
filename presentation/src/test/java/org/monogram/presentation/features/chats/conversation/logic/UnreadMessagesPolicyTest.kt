package org.monogram.presentation.features.chats.conversation.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.features.chats.conversation.ChatComponent

class UnreadMessagesPolicyTest {
    @Test
    fun `opening chat with unread count starts unread session`() {
        val state = ChatComponent.State(chatId = CHAT_ID, lastReadInboxMessageId = 100L)
            .withUnreadSessionFromChat(
                chatUnreadCount = 2,
                chatLastReadInboxMessageId = 100L
            )

        assertEquals(2, state.unreadCount)
        assertEquals(2, state.unreadSeparatorCount)
        assertEquals(100L, state.unreadSeparatorLastReadInboxMessageId)
    }

    @Test
    fun `two incoming messages away from bottom increment unread count twice`() {
        val first = message(id = 101L)
        val second = message(id = 102L)

        val stateAfterFirst = ChatComponent.State(
            chatId = CHAT_ID,
            isAtBottom = false,
            lastReadInboxMessageId = 100L
        ).withIncomingUnreadMessage(CHAT_ID, first)

        val stateAfterSecond = stateAfterFirst
            .copy(messages = listOf(first))
            .withIncomingUnreadMessage(CHAT_ID, second)

        assertEquals(2, stateAfterSecond.unreadCount)
        assertEquals(2, stateAfterSecond.unreadSeparatorCount)
    }

    @Test
    fun `inbox read update decreases count and clears separator at zero`() {
        val initial = ChatComponent.State(
            chatId = CHAT_ID,
            isAtBottom = false,
            messages = listOf(message(id = 101L), message(id = 102L)),
            unreadCount = 2,
            unreadSeparatorCount = 2,
            lastReadInboxMessageId = 100L,
            unreadSeparatorLastReadInboxMessageId = 100L
        )

        val afterFirstRead = initial.withInboxReadUpdate(
            readChatId = CHAT_ID,
            readMessageId = 101L,
            updateUnreadSession = true
        )
        val afterSecondRead = afterFirstRead.withInboxReadUpdate(
            readChatId = CHAT_ID,
            readMessageId = 102L,
            updateUnreadSession = true
        )

        assertEquals(1, afterFirstRead.unreadCount)
        assertEquals(1, afterFirstRead.unreadSeparatorCount)
        assertEquals(101L, afterFirstRead.lastReadInboxMessageId)
        assertTrue(afterFirstRead.messages.first { it.id == 101L }.isRead)
        assertEquals(0, afterSecondRead.unreadCount)
        assertEquals(0, afterSecondRead.unreadSeparatorCount)
        assertEquals(0L, afterSecondRead.unreadSeparatorLastReadInboxMessageId)
    }

    @Test
    fun `incoming message at bottom does not increase unread count`() {
        val state = ChatComponent.State(chatId = CHAT_ID, isAtBottom = true)
            .withIncomingUnreadMessage(CHAT_ID, message(id = 101L))

        assertEquals(0, state.unreadCount)
        assertEquals(0, state.unreadSeparatorCount)
    }

    private fun message(id: Long): MessageModel =
        MessageModel(
            id = id,
            date = id.toInt(),
            isOutgoing = false,
            senderName = "sender",
            chatId = CHAT_ID,
            content = MessageContent.Text("text")
        )

    private companion object {
        const val CHAT_ID = 1L
    }
}
