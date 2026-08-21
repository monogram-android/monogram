package org.monogram.presentation.features.chats.conversation.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.ChatViewportCacheEntry
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.repository.BoundaryState
import org.monogram.domain.repository.HistoryPage
import org.monogram.domain.repository.HistorySource

class ChatInitialLoadPolicyTest {
    @Test
    fun `network reconciliation is skipped only for a full main chat local page`() {
        val fullLocalPage = localPage(size = 50)

        assertFalse(
            shouldRequestInitialNetwork(
                fullLocalPage,
                requestedLimit = 50,
                threadId = null
            )
        )
        assertTrue(
            shouldRequestInitialNetwork(
                localPage(size = 49),
                requestedLimit = 50,
                threadId = null
            )
        )
        assertTrue(shouldRequestInitialNetwork(fullLocalPage, requestedLimit = 50, threadId = 10L))
    }

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
            savedViewport = ChatViewportCacheEntry(
                anchorMessageId = 20L,
                anchorOffsetPx = 4,
                atBottom = false
            ),
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
            savedViewport = ChatViewportCacheEntry(
                anchorMessageId = 20L,
                anchorOffsetPx = 4,
                atBottom = false
            ),
            firstUnreadMessageId = null,
            unreadCount = 0,
            rootMessageId = null
        )
        val next = buildChatInitialLoadKey(
            chatId = 1L,
            effectiveThreadId = 2L,
            explicitMessageId = null,
            savedViewport = ChatViewportCacheEntry(
                anchorMessageId = 21L,
                anchorOffsetPx = 4,
                atBottom = false
            ),
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
            savedViewport = ChatViewportCacheEntry(
                anchorMessageId = 42L,
                anchorOffsetPx = 0,
                atBottom = false
            ),
            firstUnreadMessageId = null,
            unreadCount = 0,
            rootMessageId = null
        )
        val explicitMessageKey = buildChatInitialLoadKey(
            chatId = 1L,
            effectiveThreadId = null,
            explicitMessageId = 42L,
            savedViewport = ChatViewportCacheEntry(
                anchorMessageId = 42L,
                anchorOffsetPx = 0,
                atBottom = false
            ),
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

    private fun localPage(size: Int) = HistoryPage(
        messages = (1..size).map { index ->
            MessageModel(
                id = index.toLong(),
                date = index,
                isOutgoing = false,
                senderName = "sender",
                chatId = 1L,
                content = MessageContent.Text("message")
            )
        },
        olderBoundary = BoundaryState.Open,
        newerBoundary = BoundaryState.Open,
        source = HistorySource.LocalSnapshot
    )
}
