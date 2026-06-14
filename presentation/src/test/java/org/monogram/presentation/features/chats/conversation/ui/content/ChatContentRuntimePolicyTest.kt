package org.monogram.presentation.features.chats.conversation.ui.content

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.presentation.features.chats.conversation.ChatScrollCommand
import org.monogram.presentation.features.chats.conversation.ChatViewportPhase

class ChatContentRuntimePolicyTest {
    @Test
    fun `shouldSuppressMessageEntryAnimations only depends on initial loading and viewport lifecycle`() {
        assertFalse(
            shouldSuppressMessageEntryAnimations(
                showInitialLoading = false,
                viewportPhase = ChatViewportPhase.Settled
            )
        )
        assertTrue(
            shouldSuppressMessageEntryAnimations(
                showInitialLoading = true,
                viewportPhase = ChatViewportPhase.Settled
            )
        )
        assertTrue(
            shouldSuppressMessageEntryAnimations(
                showInitialLoading = false,
                viewportPhase = ChatViewportPhase.Restoring
            )
        )
    }

    @Test
    fun `shouldRetainBottomAlignmentAfterContentChange skips already aligned updates without pending command`() {
        assertFalse(
            shouldRetainBottomAlignmentAfterContentChange(
                viewportPhase = ChatViewportPhase.Settled,
                pendingScrollCommand = null,
                stateIsAtBottom = true,
                measuredIsAtBottom = true,
                isLoading = false,
                isLoadingOlder = false,
                isLoadingNewer = false,
                isScrollInProgress = false,
                bottomAlignmentDeltaPx = 0.5f
            )
        )
    }

    @Test
    fun `shouldRetainBottomAlignmentAfterContentChange skips while scroll command is pending`() {
        assertFalse(
            shouldRetainBottomAlignmentAfterContentChange(
                viewportPhase = ChatViewportPhase.Settled,
                pendingScrollCommand = ChatScrollCommand.ScrollToBottom(animated = true),
                stateIsAtBottom = true,
                measuredIsAtBottom = true,
                isLoading = false,
                isLoadingOlder = false,
                isLoadingNewer = false,
                isScrollInProgress = false,
                bottomAlignmentDeltaPx = 24f
            )
        )
    }

    @Test
    fun `shouldRetainBottomAlignmentAfterContentChange requests correction when bottom alignment is lost`() {
        assertTrue(
            shouldRetainBottomAlignmentAfterContentChange(
                viewportPhase = ChatViewportPhase.Settled,
                pendingScrollCommand = null,
                stateIsAtBottom = true,
                measuredIsAtBottom = false,
                isLoading = false,
                isLoadingOlder = false,
                isLoadingNewer = false,
                isScrollInProgress = false,
                bottomAlignmentDeltaPx = 18f
            )
        )
    }
}
