package org.monogram.presentation.features.chats.common

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatExitActionTest {
    @Test
    fun `returns delete for private chat with delete permission`() {
        assertEquals(
            ChatExitAction.Delete,
            resolveChatExitAction(
                isMainChat = true,
                isGroup = false,
                isChannel = false,
                isMember = true,
                canDeleteChat = true
            )
        )
    }

    @Test
    fun `returns leave for member group`() {
        assertEquals(
            ChatExitAction.Leave,
            resolveChatExitAction(
                isMainChat = true,
                isGroup = true,
                isChannel = false,
                isMember = true,
                canDeleteChat = false
            )
        )
    }

    @Test
    fun `returns leave for member channel`() {
        assertEquals(
            ChatExitAction.Leave,
            resolveChatExitAction(
                isMainChat = true,
                isGroup = false,
                isChannel = true,
                isMember = true,
                canDeleteChat = false
            )
        )
    }

    @Test
    fun `returns none for non member group`() {
        assertEquals(
            ChatExitAction.None,
            resolveChatExitAction(
                isMainChat = true,
                isGroup = true,
                isChannel = false,
                isMember = false,
                canDeleteChat = false
            )
        )
    }

    @Test
    fun `returns none for non main chat`() {
        assertEquals(
            ChatExitAction.None,
            resolveChatExitAction(
                isMainChat = false,
                isGroup = false,
                isChannel = false,
                isMember = true,
                canDeleteChat = true
            )
        )
    }
}
