package org.monogram.presentation.features.chats.conversation.ui.content

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.presentation.features.chats.conversation.ChatRenderMode
import org.monogram.presentation.features.chats.conversation.ChatScrollCommand
import org.monogram.presentation.features.chats.conversation.ChatViewportPhase
import org.monogram.presentation.features.chats.conversation.updateChatContentVisibilityLatch

class ChatContentRuntimePolicyTest {
    @Test
    fun `updateChatContentVisibilityLatch keeps active chat visible after first settle through reopen sequence`() {
        val afterFirstSettle = updateChatContentVisibilityLatch(
            previousVisible = false,
            renderMode = ChatRenderMode.Active,
            viewportPhase = ChatViewportPhase.Settled
        )
        val afterCloseInfo = updateChatContentVisibilityLatch(
            previousVisible = afterFirstSettle,
            renderMode = ChatRenderMode.Active,
            viewportPhase = ChatViewportPhase.Restoring
        )
        val afterReopenInfo = updateChatContentVisibilityLatch(
            previousVisible = afterCloseInfo,
            renderMode = ChatRenderMode.Active,
            viewportPhase = ChatViewportPhase.Initializing
        )

        assertTrue(afterFirstSettle)
        assertTrue(afterCloseInfo)
        assertTrue(afterReopenInfo)
    }

    @Test
    fun `shouldAutoSettleViewportAfterContentReady settles cached chat content while remote load continues`() {
        assertTrue(
            shouldAutoSettleViewportAfterContentReady(
                viewportPhase = ChatViewportPhase.Initializing,
                pendingScrollCommand = null,
                hasMessages = true,
                viewAsTopics = false,
                currentTopicId = null,
                topicsCount = 0
            )
        )
    }

    @Test
    fun `shouldAutoSettleViewportAfterContentReady waits for pending scroll command`() {
        assertFalse(
            shouldAutoSettleViewportAfterContentReady(
                viewportPhase = ChatViewportPhase.Restoring,
                pendingScrollCommand = ChatScrollCommand.ScrollToBottom(animated = false),
                hasMessages = true,
                viewAsTopics = false,
                currentTopicId = null,
                topicsCount = 0
            )
        )
    }

    @Test
    fun `shouldAutoSettleViewportAfterContentReady ignores empty initializing state`() {
        assertFalse(
            shouldAutoSettleViewportAfterContentReady(
                viewportPhase = ChatViewportPhase.Initializing,
                pendingScrollCommand = null,
                hasMessages = false,
                viewAsTopics = false,
                currentTopicId = null,
                topicsCount = 0
            )
        )
    }

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

    @Test
    fun `shouldAutoFollowLatestAfterContentChange follows when last grouped message changes and follow is armed`() {
        assertTrue(
            shouldAutoFollowLatestAfterContentChange(
                previousLastGroupedMessageId = 10L,
                currentLastGroupedMessageId = 11L,
                followLatestArmed = true,
                viewportPhase = ChatViewportPhase.Settled,
                pendingScrollCommand = null,
                isLoading = false,
                isLoadingOlder = false,
                isLoadingNewer = false,
                isScrollInProgress = false,
                showInitialLoading = false,
                isLatestLoaded = true
            )
        )
    }

    @Test
    fun `updateFollowLatestArmed stays armed through passive layout shifts after new content`() {
        assertTrue(
            updateFollowLatestArmed(
                previousArmed = true,
                isNearBottom = false,
                hasUserScrolledAwayFromBottom = false,
                isDragged = false
            )
        )
    }

    @Test
    fun `updateFollowLatestArmed disarms after real user scroll away`() {
        assertFalse(
            updateFollowLatestArmed(
                previousArmed = true,
                isNearBottom = false,
                hasUserScrolledAwayFromBottom = true,
                isDragged = false
            )
        )
        assertFalse(
            updateFollowLatestArmed(
                previousArmed = true,
                isNearBottom = false,
                hasUserScrolledAwayFromBottom = false,
                isDragged = true
            )
        )
    }

    @Test
    fun `updateFollowLatestArmed rearms when viewport returns near bottom`() {
        assertTrue(
            updateFollowLatestArmed(
                previousArmed = false,
                isNearBottom = true,
                hasUserScrolledAwayFromBottom = false,
                isDragged = false
            )
        )
    }

    @Test
    fun `shouldAutoFollowLatestAfterContentChange does not follow when user is outside near-bottom zone`() {
        assertFalse(
            shouldAutoFollowLatestAfterContentChange(
                previousLastGroupedMessageId = 10L,
                currentLastGroupedMessageId = 11L,
                followLatestArmed = false,
                viewportPhase = ChatViewportPhase.Settled,
                pendingScrollCommand = null,
                isLoading = false,
                isLoadingOlder = false,
                isLoadingNewer = false,
                isScrollInProgress = false,
                showInitialLoading = false,
                isLatestLoaded = true
            )
        )
    }

    @Test
    fun `shouldAutoFollowLatestAfterContentChange does not follow during pending command loading or dragging`() {
        assertFalse(
            shouldAutoFollowLatestAfterContentChange(
                previousLastGroupedMessageId = 10L,
                currentLastGroupedMessageId = 11L,
                followLatestArmed = true,
                viewportPhase = ChatViewportPhase.Settled,
                pendingScrollCommand = ChatScrollCommand.ScrollToBottom(animated = true),
                isLoading = false,
                isLoadingOlder = false,
                isLoadingNewer = false,
                isScrollInProgress = false,
                showInitialLoading = false,
                isLatestLoaded = true
            )
        )
        assertFalse(
            shouldAutoFollowLatestAfterContentChange(
                previousLastGroupedMessageId = 10L,
                currentLastGroupedMessageId = 11L,
                followLatestArmed = true,
                viewportPhase = ChatViewportPhase.Settled,
                pendingScrollCommand = null,
                isLoading = false,
                isLoadingOlder = false,
                isLoadingNewer = true,
                isScrollInProgress = false,
                showInitialLoading = false,
                isLatestLoaded = true
            )
        )
        assertFalse(
            shouldAutoFollowLatestAfterContentChange(
                previousLastGroupedMessageId = 10L,
                currentLastGroupedMessageId = 11L,
                followLatestArmed = true,
                viewportPhase = ChatViewportPhase.Settled,
                pendingScrollCommand = null,
                isLoading = false,
                isLoadingOlder = false,
                isLoadingNewer = false,
                isScrollInProgress = true,
                showInitialLoading = false,
                isLatestLoaded = true
            )
        )
    }

    @Test
    fun `shouldAutoFollowLatestAfterContentChange ignores content updates when last grouped message is unchanged`() {
        assertFalse(
            shouldAutoFollowLatestAfterContentChange(
                previousLastGroupedMessageId = 10L,
                currentLastGroupedMessageId = 10L,
                followLatestArmed = true,
                viewportPhase = ChatViewportPhase.Settled,
                pendingScrollCommand = null,
                isLoading = false,
                isLoadingOlder = false,
                isLoadingNewer = false,
                isScrollInProgress = false,
                showInitialLoading = false,
                isLatestLoaded = true
            )
        )
    }

    @Test
    fun `near-bottom follow is independent from exact-bottom correction`() {
        assertTrue(
            shouldAutoFollowLatestAfterContentChange(
                previousLastGroupedMessageId = 10L,
                currentLastGroupedMessageId = 11L,
                followLatestArmed = true,
                viewportPhase = ChatViewportPhase.Settled,
                pendingScrollCommand = null,
                isLoading = false,
                isLoadingOlder = false,
                isLoadingNewer = false,
                isScrollInProgress = false,
                showInitialLoading = false,
                isLatestLoaded = true
            )
        )
        assertFalse(
            shouldRetainBottomAlignmentAfterContentChange(
                viewportPhase = ChatViewportPhase.Settled,
                pendingScrollCommand = null,
                stateIsAtBottom = false,
                measuredIsAtBottom = false,
                isLoading = false,
                isLoadingOlder = false,
                isLoadingNewer = false,
                isScrollInProgress = false,
                bottomAlignmentDeltaPx = 18f
            )
        )
    }

    @Test
    fun `shouldDisarmFollowLatest only for history-oriented commands`() {
        assertTrue(
            shouldDisarmFollowLatest(
                ChatScrollCommand.JumpToMessage(
                    messageId = 1L,
                    highlight = false
                )
            )
        )
        assertTrue(shouldDisarmFollowLatest(ChatScrollCommand.ScrollToStart(animated = true)))
        assertTrue(
            shouldDisarmFollowLatest(
                ChatScrollCommand.RestoreViewport(
                    anchorMessageId = 1L,
                    anchorOffsetPx = 10,
                    atBottom = false
                )
            )
        )
        assertFalse(shouldDisarmFollowLatest(ChatScrollCommand.ScrollToBottom(animated = true)))
        assertFalse(
            shouldDisarmFollowLatest(
                ChatScrollCommand.RestoreViewport(
                    anchorMessageId = null,
                    anchorOffsetPx = 0,
                    atBottom = true
                )
            )
        )
    }

    @Test
    fun `visible message read reporting starts disabled on initial open`() {
        val context = VisibleMessageReadReportingContext(
            chatId = 1L,
            currentTopicId = null,
            rootMessageId = null
        )

        val state = updateVisibleMessageReadReportingState(
            previousState = null,
            nextContext = context,
            hasUserDraggedList = false
        )

        assertFalse(
            shouldReportVisibleMessageAsRead(
                reportingState = state,
                context = context
            )
        )
    }

    @Test
    fun `visible message read reporting enables after first real user scroll`() {
        val context = VisibleMessageReadReportingContext(
            chatId = 1L,
            currentTopicId = 2L,
            rootMessageId = null
        )

        val state = updateVisibleMessageReadReportingState(
            previousState = null,
            nextContext = context,
            hasUserDraggedList = true
        )

        assertTrue(
            shouldReportVisibleMessageAsRead(
                reportingState = state,
                context = context
            )
        )
    }

    @Test
    fun `visible message read reporting stays disabled through passive restore without user scroll`() {
        val context = VisibleMessageReadReportingContext(
            chatId = 1L,
            currentTopicId = null,
            rootMessageId = 99L
        )

        val initial = updateVisibleMessageReadReportingState(
            previousState = null,
            nextContext = context,
            hasUserDraggedList = false
        )
        val afterPassiveSettle = updateVisibleMessageReadReportingState(
            previousState = initial,
            nextContext = context,
            hasUserDraggedList = false
        )

        assertFalse(
            shouldReportVisibleMessageAsRead(
                reportingState = afterPassiveSettle,
                context = context
            )
        )
    }

    @Test
    fun `visible message read reporting resets when switching conversation context`() {
        val firstContext = VisibleMessageReadReportingContext(
            chatId = 1L,
            currentTopicId = null,
            rootMessageId = null
        )
        val secondContext = VisibleMessageReadReportingContext(
            chatId = 1L,
            currentTopicId = 10L,
            rootMessageId = null
        )

        val enabledInFirstContext = updateVisibleMessageReadReportingState(
            previousState = null,
            nextContext = firstContext,
            hasUserDraggedList = true
        )
        val resetForSecondContext = updateVisibleMessageReadReportingState(
            previousState = enabledInFirstContext,
            nextContext = secondContext,
            hasUserDraggedList = false
        )

        assertTrue(
            shouldReportVisibleMessageAsRead(
                reportingState = enabledInFirstContext,
                context = firstContext
            )
        )
        assertFalse(
            shouldReportVisibleMessageAsRead(
                reportingState = resetForSecondContext,
                context = secondContext
            )
        )
    }
}
