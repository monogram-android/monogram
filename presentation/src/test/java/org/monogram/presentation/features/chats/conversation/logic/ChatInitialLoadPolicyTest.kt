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
            initialMessageId = null,
            savedViewport = null,
            firstUnreadMessageId = null,
            rootMessageId = null
        )

        assertTrue(shouldStartInitialLoad(null, key, hasStartedForCurrentContext = false))
    }

    @Test
    fun `same context does not start initial load twice`() {
        val key = buildChatInitialLoadKey(
            chatId = 1L,
            effectiveThreadId = 2L,
            initialMessageId = 10L,
            savedViewport = ChatViewportCacheEntry(20L, 4, false),
            firstUnreadMessageId = 30L,
            rootMessageId = 40L
        )

        assertFalse(shouldStartInitialLoad(key, key, hasStartedForCurrentContext = true))
    }

    @Test
    fun `changed viewport anchor starts a new initial load`() {
        val previous = buildChatInitialLoadKey(
            chatId = 1L,
            effectiveThreadId = 2L,
            initialMessageId = null,
            savedViewport = ChatViewportCacheEntry(20L, 4, false),
            firstUnreadMessageId = null,
            rootMessageId = null
        )
        val next = buildChatInitialLoadKey(
            chatId = 1L,
            effectiveThreadId = 2L,
            initialMessageId = null,
            savedViewport = ChatViewportCacheEntry(21L, 4, false),
            firstUnreadMessageId = null,
            rootMessageId = null
        )

        assertTrue(shouldStartInitialLoad(previous, next, hasStartedForCurrentContext = true))
    }
}
