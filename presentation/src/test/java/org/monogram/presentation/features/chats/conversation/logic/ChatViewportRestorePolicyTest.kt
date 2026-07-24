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
            chatId = 1L,
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
        assertEquals(InitialChatScrollTargetOrigin.ExplicitMessage, around.origin)
        assertTrue(around.highlight)
        assertFalse(around.backfillNewerAfterInitialLoad)
        assertEquals(99L, (around.command as ChatScrollCommand.JumpToMessage).messageId)
        assertEquals("around.explicit", target.perfTargetName())
    }

    @Test
    fun `saved non-bottom viewport wins over unread on repeat open`() {
        val target = resolveInitialChatScrollTarget(
            chatId = 1L,
            explicitMessageId = null,
            savedViewport = ChatViewportCacheEntry(
                anchorMessageId = 42L,
                anchorAliasIds = listOf(41L, 43L),
                anchorOffsetPx = 12,
                atBottom = false,
                readFully = false
            ),
            firstUnreadMessageId = 50L,
            isComments = false
        )

        val around = target as InitialChatScrollTarget.AroundMessage
        val command = around.command as ChatScrollCommand.RestoreViewport
        assertEquals(42L, around.messageId)
        assertEquals(InitialChatScrollTargetOrigin.SavedViewport, around.origin)
        assertFalse(around.highlight)
        assertFalse(around.backfillNewerAfterInitialLoad)
        assertEquals(listOf(41L, 43L), command.anchorAliasIds)
        assertEquals(12, command.anchorOffsetPx)
        assertEquals("around.saved_viewport", target.perfTargetName())
    }

    @Test
    fun `saved read fully viewport yields to unread`() {
        val target = resolveInitialChatScrollTarget(
            chatId = 1L,
            explicitMessageId = null,
            savedViewport = ChatViewportCacheEntry(
                anchorMessageId = 42L,
                anchorOffsetPx = 12,
                atBottom = false,
                readFully = true
            ),
            firstUnreadMessageId = 50L,
            isComments = false
        )

        val around = target as InitialChatScrollTarget.AroundMessage
        assertEquals(50L, around.messageId)
        assertEquals(InitialChatScrollTargetOrigin.FirstUnread, around.origin)
    }

    @Test
    fun `saved viewport can restore from alias when primary anchor is absent`() {
        val target = resolveInitialChatScrollTarget(
            chatId = 1L,
            explicitMessageId = null,
            savedViewport = ChatViewportCacheEntry(
                anchorMessageId = null,
                anchorAliasIds = listOf(77L, 78L),
                anchorOffsetPx = 8,
                atBottom = false,
                readFully = false
            ),
            firstUnreadMessageId = null,
            isComments = false
        )

        val around = target as InitialChatScrollTarget.AroundMessage
        val command = around.command as ChatScrollCommand.RestoreViewport
        assertEquals(77L, around.messageId)
        assertEquals(listOf(77L, 78L), command.anchorAliasIds)
    }

    @Test
    fun `unread wins when saved viewport is absent without backfill for small unread`() {
        val target = resolveInitialChatScrollTarget(
            chatId = 1L,
            explicitMessageId = null,
            savedViewport = null,
            firstUnreadMessageId = 50L,
            unreadCount = 10,
            isComments = false
        )

        val around = target as InitialChatScrollTarget.AroundMessage
        assertEquals(50L, around.messageId)
        assertEquals(InitialChatScrollTargetOrigin.FirstUnread, around.origin)
        assertFalse(around.highlight)
        assertFalse(around.backfillNewerAfterInitialLoad)
    }

    @Test
    fun `large unread root chat requests newer backfill`() {
        val target = resolveInitialChatScrollTarget(
            chatId = 1L,
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
        assertEquals("around.first_unread", target.perfTargetName())
    }

    @Test
    fun `large unread comments do not request newer backfill`() {
        val target = resolveInitialChatScrollTarget(
            chatId = 1L,
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
    fun `saved at-bottom with unread opens around first unread`() {
        val target = resolveInitialChatScrollTarget(
            chatId = 1L,
            explicitMessageId = null,
            savedViewport = ChatViewportCacheEntry(
                anchorMessageId = null,
                anchorOffsetPx = 0,
                atBottom = true
            ),
            firstUnreadMessageId = 50L,
            isComments = false
        )

        val around = target as InitialChatScrollTarget.AroundMessage
        assertEquals(50L, around.messageId)
        assertFalse(around.highlight)
        assertEquals(50L, (around.command as ChatScrollCommand.JumpToMessage).messageId)
    }

    @Test
    fun `saved at-bottom without unread opens bottom`() {
        val target = resolveInitialChatScrollTarget(
            chatId = 1L,
            explicitMessageId = null,
            savedViewport = ChatViewportCacheEntry(
                anchorMessageId = null,
                anchorOffsetPx = 0,
                atBottom = true
            ),
            firstUnreadMessageId = null,
            isComments = false
        )

        val bottom = target as InitialChatScrollTarget.Bottom
        assertEquals(InitialChatScrollTargetOrigin.BottomSavedViewport, bottom.origin)
        assertTrue((bottom.command as ChatScrollCommand.RestoreViewport).atBottom)
        assertEquals("bottom.bottom_saved", target.perfTargetName())
    }

    @Test
    fun `comments without saved viewport open from start`() {
        val target = resolveInitialChatScrollTarget(
            chatId = 1L,
            explicitMessageId = null,
            savedViewport = null,
            firstUnreadMessageId = 50L,
            isComments = true
        )

        val comments = target as InitialChatScrollTarget.Comments
        assertEquals(InitialChatScrollTargetOrigin.CommentsStart, comments.origin)
        assertTrue(comments.command is ChatScrollCommand.ScrollToStart)
        assertEquals("comments.comments_start", target.perfTargetName())
    }

    @Test
    fun `comments with saved at-bottom and unread restore saved viewport`() {
        val target = resolveInitialChatScrollTarget(
            chatId = 1L,
            explicitMessageId = null,
            savedViewport = ChatViewportCacheEntry(
                anchorMessageId = null,
                anchorOffsetPx = 0,
                atBottom = true
            ),
            firstUnreadMessageId = 50L,
            isComments = true
        )

        val comments = target as InitialChatScrollTarget.Comments
        assertEquals(InitialChatScrollTargetOrigin.CommentsSavedViewport, comments.origin)
        val command = comments.command as ChatScrollCommand.RestoreViewport
        assertTrue(command.atBottom)
        assertEquals("comments.comments_saved", target.perfTargetName())
    }

    @Test
    fun `saved viewport with mismatched anchor chat id is ignored`() {
        val target = resolveInitialChatScrollTarget(
            chatId = 1L,
            explicitMessageId = null,
            savedViewport = ChatViewportCacheEntry(
                anchorMessageId = 42L,
                anchorOffsetPx = 12,
                atBottom = false,
                readFully = false,
                anchorChatId = 2L
            ),
            firstUnreadMessageId = null,
            isComments = false
        )

        val bottom = target as InitialChatScrollTarget.Bottom
        assertEquals(InitialChatScrollTargetOrigin.BottomFallback, bottom.origin)
        assertTrue(bottom.command is ChatScrollCommand.ScrollToBottom)
    }
}
