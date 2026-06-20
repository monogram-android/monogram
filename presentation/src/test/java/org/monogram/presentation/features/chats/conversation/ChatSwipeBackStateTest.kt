package org.monogram.presentation.features.chats.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.presentation.core.ui.ScreenSwipeBackAction
import org.monogram.presentation.core.ui.ScreenSwipeBackPreview

class ChatSwipeBackStateTest {
    @Test
    fun `current topic exposes local topic close swipe`() {
        val swipeState = resolveChatSwipeBackState(
            state = ChatComponent.State(
                viewAsTopics = true,
                currentTopicId = 42L,
            )
        )

        assertEquals(ScreenSwipeBackAction.LocalChatTopicClose, swipeState.action)
        assertEquals(ScreenSwipeBackPreview.ChatForumList, swipeState.preview)
        assertFalse(swipeState.isBlocked)
        assertTrue(swipeState.isSupported)
    }

    @Test
    fun `no topic exposes normal chat back behavior`() {
        val swipeState = resolveChatSwipeBackState(
            state = ChatComponent.State(
                viewAsTopics = true,
                currentTopicId = null,
            )
        )

        assertEquals(ScreenSwipeBackAction.StackPop, swipeState.action)
        assertEquals(ScreenSwipeBackPreview.PreviousStackEntry, swipeState.preview)
    }

    @Test
    fun `viewer state still blocks swipe`() {
        val swipeState = resolveChatSwipeBackState(
            state = ChatComponent.State(
                fullScreenImages = listOf("file://preview.jpg"),
            )
        )

        assertTrue(swipeState.isBlocked)
    }

    @Test
    fun `transient overlay blocks swipe`() {
        val swipeState = resolveChatSwipeBackState(
            state = ChatComponent.State(),
            hasTransientBlockingOverlay = true,
        )

        assertTrue(swipeState.isBlocked)
    }
}
