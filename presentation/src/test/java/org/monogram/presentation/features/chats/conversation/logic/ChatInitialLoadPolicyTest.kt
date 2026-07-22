package org.monogram.presentation.features.chats.conversation.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.ChatViewportCacheEntry

class ChatInitialLoadPolicyTest {
    @Test
    fun `initial load starts when context has not started yet`() {
        val key = buildChatInitialLoadKey(
            chatId = 1L,
            effectiveThreadId = null,
            explicitMessageId = null,
            savedViewport = null,
            firstUnreadMessageId = null,
            unreadCount = 0,
            rootMessageId = null
        )

        assertTrue(shouldStartInitialLoad(null, key, hasStartedForCurrentContext = false))
    }

    @Test
    fun `same context does not start initial load twice`() {
        val key = buildChatInitialLoadKey(
            chatId = 1L,
            effectiveThreadId = 2L,
            explicitMessageId = 10L,
            savedViewport = ChatViewportCacheEntry(20L, 4, false),
            firstUnreadMessageId = 30L,
            unreadCount = 1,
            rootMessageId = 40L
        )

        assertFalse(shouldStartInitialLoad(key, key, hasStartedForCurrentContext = true))
    }

    @Test
    fun `changed viewport anchor starts a new initial load`() {
        val previous = buildChatInitialLoadKey(
            chatId = 1L,
            effectiveThreadId = 2L,
            explicitMessageId = null,
            savedViewport = ChatViewportCacheEntry(20L, 4, false),
            firstUnreadMessageId = null,
            unreadCount = 0,
            rootMessageId = null
        )
        val next = buildChatInitialLoadKey(
            chatId = 1L,
            effectiveThreadId = 2L,
            explicitMessageId = null,
            savedViewport = ChatViewportCacheEntry(21L, 4, false),
            firstUnreadMessageId = null,
            unreadCount = 0,
            rootMessageId = null
        )

        assertTrue(shouldStartInitialLoad(previous, next, hasStartedForCurrentContext = true))
    }

    @Test
    fun `same anchor with different source starts a new initial load`() {
        val savedViewportKey = buildChatInitialLoadKey(
            chatId = 1L,
            effectiveThreadId = null,
            explicitMessageId = null,
            savedViewport = ChatViewportCacheEntry(42L, 0, false),
            firstUnreadMessageId = null,
            unreadCount = 0,
            rootMessageId = null
        )
        val explicitMessageKey = buildChatInitialLoadKey(
            chatId = 1L,
            effectiveThreadId = null,
            explicitMessageId = 42L,
            savedViewport = ChatViewportCacheEntry(42L, 0, false),
            firstUnreadMessageId = null,
            unreadCount = 0,
            rootMessageId = null
        )

        assertTrue(
            shouldStartInitialLoad(
                savedViewportKey,
                explicitMessageKey,
                hasStartedForCurrentContext = true
            )
        )
    }
}
