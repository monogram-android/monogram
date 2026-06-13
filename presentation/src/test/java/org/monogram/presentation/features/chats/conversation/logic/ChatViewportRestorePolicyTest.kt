package org.monogram.presentation.features.chats.conversation.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.ChatViewportCacheEntry
import org.monogram.presentation.features.chats.conversation.ChatScrollCommand

class ChatViewportRestorePolicyTest {
    @Test
    fun `explicit message wins over saved viewport and unread`() {
        val target = resolveInitialChatScrollTarget(
            explicitMessageId = 99L,
            savedViewport = ChatViewportCacheEntry(
                anchorMessageId = 42L,
                anchorOffsetPx = 12,
                atBottom = false
            ),
            firstUnreadMessageId = 50L,
            isComments = false
        )

        val around = target as InitialChatScrollTarget.AroundMessage
        assertEquals(99L, around.messageId)
        assertTrue(around.highlight)
        assertFalse(around.backfillNewerAfterInitialLoad)
        assertEquals(99L, (around.command as ChatScrollCommand.JumpToMessage).messageId)
    }

    @Test
    fun `saved non-bottom viewport wins over unread on repeat open`() {
        val target = resolveInitialChatScrollTarget(
            explicitMessageId = null,
            savedViewport = ChatViewportCacheEntry(
                anchorMessageId = 42L,
                anchorOffsetPx = 12,
                atBottom = false
            ),
            firstUnreadMessageId = 50L,
            isComments = false
        )

        val around = target as InitialChatScrollTarget.AroundMessage
        val command = around.command as ChatScrollCommand.RestoreViewport
        assertEquals(42L, around.messageId)
        assertFalse(around.highlight)
        assertFalse(around.backfillNewerAfterInitialLoad)
        assertEquals(12, command.anchorOffsetPx)
    }

    @Test
    fun `unread wins when saved viewport is absent without backfill for small unread`() {
        val target = resolveInitialChatScrollTarget(
            explicitMessageId = null,
            savedViewport = null,
            firstUnreadMessageId = 50L,
            unreadCount = 10,
            isComments = false
        )

        val around = target as InitialChatScrollTarget.AroundMessage
        assertEquals(50L, around.messageId)
        assertFalse(around.highlight)
        assertFalse(around.backfillNewerAfterInitialLoad)
    }

    @Test
    fun `large unread root chat requests newer backfill`() {
        val target = resolveInitialChatScrollTarget(
            explicitMessageId = null,
            savedViewport = null,
            firstUnreadMessageId = 50L,
            unreadCount = 80,
            backfillUnreadThreshold = 50,
            isComments = false
        )

        val around = target as InitialChatScrollTarget.AroundMessage
        assertEquals(50L, around.messageId)
        assertFalse(around.highlight)
        assertTrue(around.backfillNewerAfterInitialLoad)
    }

    @Test
    fun `large unread comments do not request newer backfill`() {
        val target = resolveInitialChatScrollTarget(
            explicitMessageId = null,
            savedViewport = null,
            firstUnreadMessageId = 50L,
            unreadCount = 80,
            backfillUnreadThreshold = 50,
            isComments = true
        )

        assertTrue(target is InitialChatScrollTarget.Comments)
    }

    @Test
    fun `saved at-bottom opens bottom`() {
        val target = resolveInitialChatScrollTarget(
            explicitMessageId = null,
            savedViewport = ChatViewportCacheEntry(
                anchorMessageId = null,
                anchorOffsetPx = 0,
                atBottom = true
            ),
            firstUnreadMessageId = 50L,
            isComments = false
        )

        val bottom = target as InitialChatScrollTarget.Bottom
        assertTrue((bottom.command as ChatScrollCommand.RestoreViewport).atBottom)
    }

    @Test
    fun `comments without saved viewport open from start`() {
        val target = resolveInitialChatScrollTarget(
            explicitMessageId = null,
            savedViewport = null,
            firstUnreadMessageId = 50L,
            isComments = true
        )

        assertTrue((target as InitialChatScrollTarget.Comments).command is ChatScrollCommand.ScrollToStart)
    }
}
