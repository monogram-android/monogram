package org.monogram.presentation.features.chats.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatActionPolicyTest {

    @Test
    fun `private main chat resolves delete only`() {
        val policy = resolveChatActionPolicy(
            isMainChat = true,
            isGroup = false,
            isChannel = false,
            isMember = true,
            canDeleteChat = true,
            canReport = true,
            canJoin = false,
            canBlockOrUnblock = true,
            canPin = false,
            context = ChatActionScreenContext.Chat
        )

        assertEquals(ChatExitAction.Delete, policy.exitAction)
        assertTrue(policy.canClearHistory)
        assertTrue(policy.canReport)
        assertFalse(policy.canJoin)
    }

    @Test
    fun `group member resolves leave only`() {
        val policy = resolveChatActionPolicy(
            isMainChat = true,
            isGroup = true,
            isChannel = false,
            isMember = true,
            canDeleteChat = false,
            canReport = true,
            canJoin = false,
            canBlockOrUnblock = false,
            canPin = true,
            context = ChatActionScreenContext.Profile
        )

        assertEquals(ChatExitAction.Leave, policy.exitAction)
        assertTrue(policy.canClearHistory)
        assertFalse(policy.canJoin)
    }

    @Test
    fun `non member channel resolves join and no destructive action`() {
        val policy = resolveChatActionPolicy(
            isMainChat = true,
            isGroup = false,
            isChannel = true,
            isMember = false,
            canDeleteChat = false,
            canReport = true,
            canJoin = true,
            canBlockOrUnblock = false,
            canPin = true,
            context = ChatActionScreenContext.ListSelection
        )

        assertEquals(ChatExitAction.None, policy.exitAction)
        assertTrue(policy.canJoin)
        assertFalse(policy.closeOnExitSuccess)
    }

    @Test
    fun `thread disables destructive actions`() {
        val policy = resolveChatActionPolicy(
            isMainChat = false,
            isGroup = true,
            isChannel = false,
            isMember = true,
            canDeleteChat = true,
            canReport = true,
            canJoin = false,
            canBlockOrUnblock = false,
            canPin = false,
            context = ChatActionScreenContext.Chat
        )

        assertEquals(ChatExitAction.None, policy.exitAction)
        assertFalse(policy.canClearHistory)
        assertFalse(policy.canJoin)
    }
}
