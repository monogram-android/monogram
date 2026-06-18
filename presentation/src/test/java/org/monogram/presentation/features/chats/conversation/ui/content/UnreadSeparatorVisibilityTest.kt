package org.monogram.presentation.features.chats.conversation.ui.content

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnreadSeparatorVisibilityTest {
    @Test
    fun `separator is shown when unread boundary matches item and unread session exists`() {
        assertTrue(
            shouldShowUnreadSeparator(
                isComments = false,
                unreadBoundaryIndex = 3,
                unreadSeparatorCount = 5,
                itemIndex = 3
            )
        )
    }

    @Test
    fun `separator is hidden when unread session is cleared`() {
        assertFalse(
            shouldShowUnreadSeparator(
                isComments = false,
                unreadBoundaryIndex = 3,
                unreadSeparatorCount = 0,
                itemIndex = 3
            )
        )
    }

    @Test
    fun `separator is hidden for non-boundary items and comments mode`() {
        assertFalse(
            shouldShowUnreadSeparator(
                isComments = false,
                unreadBoundaryIndex = 3,
                unreadSeparatorCount = 2,
                itemIndex = 2
            )
        )
        assertFalse(
            shouldShowUnreadSeparator(
                isComments = true,
                unreadBoundaryIndex = 3,
                unreadSeparatorCount = 2,
                itemIndex = 3
            )
        )
    }
}
