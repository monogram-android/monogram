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
                MessageModel(
                    id = 42L,
                    date = 0,
                    isOutgoing = true,
                    senderName = "me",
                    chatId = 1L,
                    content = MessageContent.Text("hello")
                )
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
    fun `shouldHideChatContentForViewportTransition only hides active non-settled viewport`() {
        assertFalse(
            shouldHideChatContentForViewportTransition(
                renderMode = ChatRenderMode.Active,
                viewportPhase = ChatViewportPhase.Settled
            )
        )
        assertTrue(
            shouldHideChatContentForViewportTransition(
                renderMode = ChatRenderMode.Active,
                viewportPhase = ChatViewportPhase.Restoring
            )
        )
        assertFalse(
            shouldHideChatContentForViewportTransition(
                renderMode = ChatRenderMode.SwipePreview,
                viewportPhase = ChatViewportPhase.Restoring
            )
        )
    }
}
