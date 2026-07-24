package org.monogram.presentation.features.chats.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.features.chats.conversation.logic.enqueueRuntimeScrollToBottom

class ChatRuntimeScrollBehaviorTest {
    @Test
    fun `enqueueRuntimeScrollToBottom keeps settled viewport while queuing runtime scroll`() {
        val initial = ChatComponent.State(
            messages = listOf(
                message(id = 42L, text = "hello", isOutgoing = true, senderName = "me")
            ),
            isLatestLoaded = true,
            viewportPhase = ChatViewportPhase.Settled
        )

        val updated = initial.enqueueRuntimeScrollToBottom(animated = true)

        assertTrue(updated.isAtBottom)
        assertEquals(ChatViewportPhase.Settled, updated.viewportPhase)
        assertEquals(
            ChatScrollCommand.ScrollToBottom(animated = true),
            updated.pendingScrollCommand
        )
    }

    @Test
    fun `updateChatContentVisibilityLatch keeps active content hidden until first settle`() {
        assertFalse(
            updateChatContentVisibilityLatch(
                previousVisible = false,
                renderMode = ChatRenderMode.Active,
                viewportPhase = ChatViewportPhase.Initializing,
                hasRenderableContent = false
            )
        )
    }

    @Test
    fun `updateChatContentVisibilityLatch shows active content on first settled viewport`() {
        assertTrue(
            updateChatContentVisibilityLatch(
                previousVisible = false,
                renderMode = ChatRenderMode.Active,
                viewportPhase = ChatViewportPhase.Settled,
                hasRenderableContent = false
            )
        )
    }

    @Test
    fun `updateChatContentVisibilityLatch does not hide active content again after settle`() {
        assertTrue(
            updateChatContentVisibilityLatch(
                previousVisible = true,
                renderMode = ChatRenderMode.Active,
                viewportPhase = ChatViewportPhase.Restoring,
                hasRenderableContent = true
            )
        )
        assertTrue(
            updateChatContentVisibilityLatch(
                previousVisible = true,
                renderMode = ChatRenderMode.Active,
                viewportPhase = ChatViewportPhase.Initializing,
                hasRenderableContent = false
            )
        )
    }

    @Test
    fun `updateChatContentVisibilityLatch keeps preview modes visible`() {
        assertTrue(
            updateChatContentVisibilityLatch(
                previousVisible = false,
                renderMode = ChatRenderMode.SwipePreview,
                viewportPhase = ChatViewportPhase.Restoring,
                hasRenderableContent = false
            )
        )
        assertTrue(
            updateChatContentVisibilityLatch(
                previousVisible = false,
                renderMode = ChatRenderMode.ForumTopicSwipePreview,
                viewportPhase = ChatViewportPhase.Initializing,
                hasRenderableContent = false
            )
        )
    }

    @Test
    fun `shouldRepairUnexpectedChatReset detects unexpected main-chat reset`() {
        val previous = ChatComponent.State(
            messages = listOf(message()),
            viewportPhase = ChatViewportPhase.Settled,
            isLoading = false,
            isAtBottom = false
        )
        val next = previous.copy(
            messages = emptyList(),
            viewportPhase = ChatViewportPhase.Initializing,
            isLoading = true,
            isAtBottom = true
        )

        assertTrue(shouldRepairUnexpectedChatReset(previous, next))
    }

    @Test
    fun `repairUnexpectedChatReset preserves previous content and viewport`() {
        val previous = ChatComponent.State(
            messages = listOf(message()),
            viewportPhase = ChatViewportPhase.Settled,
            isLoading = false,
            isLatestLoaded = true,
            isOldestLoaded = false,
            isAtBottom = false
        )
        val reset = previous.copy(
            messages = emptyList(),
            viewportPhase = ChatViewportPhase.Initializing,
            isLoading = true,
            isLatestLoaded = false,
            isOldestLoaded = false,
            isAtBottom = true
        )

        val repaired = repairUnexpectedChatReset(previous, reset)

        assertEquals(previous.messages, repaired.messages)
        assertEquals(ChatViewportPhase.Settled, repaired.viewportPhase)
        assertTrue(repaired.isLoading)
        assertFalse(repaired.isAtBottom)
        assertFalse(repaired.isLatestLoaded)
    }

    private fun message(
        id: Long = 1L,
        text: String = "cached",
        isOutgoing: Boolean = false,
        senderName: String = "user"
    ): MessageModel {
        return MessageModel(
            id = id,
            date = 0,
            isOutgoing = isOutgoing,
            senderName = senderName,
            chatId = 1L,
            content = MessageContent.Text(text)
        )
    }
}
