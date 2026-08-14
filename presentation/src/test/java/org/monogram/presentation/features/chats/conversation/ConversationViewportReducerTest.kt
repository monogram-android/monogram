package org.monogram.presentation.features.chats.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationViewportReducerTest {
    @Test
    fun `begin load preserves actor owned loading and boundaries`() {
        val state = ChatComponent.State(
            isLoading = true,
            isOldestLoaded = true,
            isLatestLoaded = true,
            pendingScrollCommand = ChatScrollCommand.ScrollToBottom(),
            viewportPhase = ChatViewportPhase.Settled
        )

        val result = ConversationViewportReducer.beginLoad(
            state = state,
            legacyOwnsLoadingState = false
        )

        assertEquals(true, result.isLoading)
        assertEquals(true, result.isOldestLoaded)
        assertEquals(true, result.isLatestLoaded)
        assertNull(result.pendingScrollCommand)
        assertEquals(ChatViewportPhase.Initializing, result.viewportPhase)
    }

    @Test
    fun `begin load resets legacy owned loading and boundaries`() {
        val result = ConversationViewportReducer.beginLoad(
            state = ChatComponent.State(isOldestLoaded = true, isLatestLoaded = true),
            legacyOwnsLoadingState = true
        )

        assertEquals(true, result.isLoading)
        assertEquals(false, result.isOldestLoaded)
        assertEquals(false, result.isLatestLoaded)
    }

    @Test
    fun `initialize clears one shot state`() {
        val state = ChatComponent.State(
            pendingScrollCommand = ChatScrollCommand.ScrollToBottom(),
            viewportPhase = ChatViewportPhase.Restoring,
            highlightRequest = MessageHighlightRequest(10L, 1L)
        )

        val result = ConversationViewportReducer.initialize(state)

        assertNull(result.pendingScrollCommand)
        assertEquals(ChatViewportPhase.Initializing, result.viewportPhase)
        assertNull(result.highlightRequest)
    }

    @Test
    fun `restore consume and settle form one shot lifecycle`() {
        val command = ChatScrollCommand.JumpToMessage(10L, highlight = true)
        val restoring = ConversationViewportReducer.restore(ChatComponent.State(), command)

        assertEquals(command, restoring.pendingScrollCommand)
        assertEquals(ChatViewportPhase.Restoring, restoring.viewportPhase)

        val consumed = ConversationViewportReducer.consumeScrollCommand(restoring)
        assertNull(consumed.pendingScrollCommand)
        assertEquals(ChatViewportPhase.Restoring, consumed.viewportPhase)

        assertEquals(
            ChatViewportPhase.Settled,
            ConversationViewportReducer.settle(consumed).viewportPhase
        )
    }

    @Test
    fun `runtime enqueue preserves settled viewport`() {
        val command = ChatScrollCommand.ScrollToBottom()
        val result = ConversationViewportReducer.enqueue(
            ChatComponent.State(viewportPhase = ChatViewportPhase.Settled),
            command
        )

        assertEquals(command, result.pendingScrollCommand)
        assertEquals(ChatViewportPhase.Settled, result.viewportPhase)
    }

    @Test
    fun `highlight requests use monotonic token and can be consumed`() {
        val first = ConversationViewportReducer.requestHighlight(ChatComponent.State(), 10L)
        val second = ConversationViewportReducer.requestHighlight(first, 20L)

        assertEquals(1L, first.highlightRequest?.token)
        assertEquals(2L, second.highlightRequest?.token)
        assertEquals(20L, second.highlightRequest?.messageId)
        assertNull(ConversationViewportReducer.consumeHighlight(second).highlightRequest)
    }

    @Test
    fun `content ready consumes target and applies only legacy boundaries`() {
        val state = ChatComponent.State(
            scrollToMessageId = 10L,
            highlightRequest = MessageHighlightRequest(10L, 1L),
            isOldestLoaded = true,
            isLatestLoaded = false
        )

        val actorOwned = ConversationViewportReducer.contentReady(
            state = state,
            isAtBottom = false
        )
        val legacyOwned = ConversationViewportReducer.contentReady(
            state = state,
            isAtBottom = true,
            legacyOldestLoaded = false,
            legacyLatestLoaded = true
        )

        assertNull(actorOwned.scrollToMessageId)
        assertNull(actorOwned.highlightRequest)
        assertEquals(true, actorOwned.isOldestLoaded)
        assertEquals(false, actorOwned.isLatestLoaded)
        assertEquals(false, actorOwned.isAtBottom)
        assertEquals(false, legacyOwned.isOldestLoaded)
        assertEquals(true, legacyOwned.isLatestLoaded)
        assertEquals(true, legacyOwned.isAtBottom)
    }
}
