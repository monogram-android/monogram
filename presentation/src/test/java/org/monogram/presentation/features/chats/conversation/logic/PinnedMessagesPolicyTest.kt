package org.monogram.presentation.features.chats.conversation.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.ChatPermissionsModel
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.features.chats.conversation.ui.content.ChatContentTopBarUiState
import org.monogram.presentation.features.chats.conversation.ui.content.shouldShowPinnedMessageAction
import org.monogram.presentation.features.chats.conversation.ui.content.shouldShowPinnedMessageBar

class PinnedMessagesPolicyTest {
    @Test
    fun `hiding pinned message only flips hidden flag`() {
        val initial = state(pinned = pinnedMessage(10L))

        val hidden = initial.hidePinnedMessage()

        assertTrue(hidden.isPinnedMessageHidden)
        assertEquals(10L, hidden.pinnedMessage?.id)
    }

    @Test
    fun `showing pinned message clears hidden flag`() {
        val initial = state(pinned = pinnedMessage(10L), hidden = true)

        val shown = initial.showPinnedMessage()

        assertFalse(shown.isPinnedMessageHidden)
        assertEquals(10L, shown.pinnedMessage?.id)
    }

    @Test
    fun `refresh keeps hidden state for the same pinned id`() {
        val initial = state(pinned = pinnedMessage(10L), hidden = true)

        val refreshed = initial.applyPinnedMessageRefresh(
            pinnedMessage = pinnedMessage(10L),
            pinnedMessageCount = 3
        )

        assertTrue(refreshed.isPinnedMessageHidden)
        assertEquals(10L, refreshed.pinnedMessage?.id)
        assertEquals(3, refreshed.pinnedMessageCount)
    }

    @Test
    fun `refresh keeps hidden state for different pinned id and null`() {
        val hidden = state(pinned = pinnedMessage(10L), hidden = true)

        val nextPinned = hidden.applyPinnedMessageRefresh(
            pinnedMessage = pinnedMessage(11L),
            pinnedMessageCount = 2
        )
        val cleared = hidden.applyPinnedMessageRefresh(
            pinnedMessage = null,
            pinnedMessageCount = 0
        )

        assertTrue(nextPinned.isPinnedMessageHidden)
        assertTrue(cleared.isPinnedMessageHidden)
    }

    @Test
    fun `show pin action is only available for hidden pinned messages in normal chat`() {
        val visible = topBarState(pinned = pinnedMessage(10L))
        val hidden = topBarState(pinned = pinnedMessage(10L), hidden = true)
        val thread = topBarState(
            pinned = pinnedMessage(10L),
            hidden = true,
            rootMessage = pinnedMessage(20L)
        )
        val search = topBarState(
            pinned = null,
            hidden = true,
            searchActive = true
        )

        assertFalse(shouldShowPinnedMessageAction(visible, isSelectionMode = false))
        assertTrue(shouldShowPinnedMessageAction(hidden, isSelectionMode = false))
        assertFalse(shouldShowPinnedMessageAction(hidden, isSelectionMode = true))
        assertFalse(shouldShowPinnedMessageAction(thread, isSelectionMode = false))
        assertFalse(shouldShowPinnedMessageAction(search, isSelectionMode = false))
    }

    @Test
    fun `pinned bar visibility respects hidden selection and thread state`() {
        val visible = topBarState(pinned = pinnedMessage(10L))
        val hidden = topBarState(pinned = pinnedMessage(10L), hidden = true)
        val thread = topBarState(pinned = pinnedMessage(10L), rootMessage = pinnedMessage(20L))

        assertTrue(shouldShowPinnedMessageBar(visible, isSelectionMode = false))
        assertFalse(shouldShowPinnedMessageBar(hidden, isSelectionMode = false))
        assertFalse(shouldShowPinnedMessageBar(visible, isSelectionMode = true))
        assertFalse(shouldShowPinnedMessageBar(thread, isSelectionMode = false))
    }

    private fun state(
        pinned: MessageModel?,
        hidden: Boolean = false
    ) = org.monogram.presentation.features.chats.conversation.ChatComponent.State(
        pinnedMessage = pinned,
        pinnedMessageCount = if (pinned != null) 1 else 0,
        isPinnedMessageHidden = hidden
    )

    private fun topBarState(
        pinned: MessageModel?,
        hidden: Boolean = false,
        rootMessage: MessageModel? = null,
        searchActive: Boolean = false
    ) = ChatContentTopBarUiState(
        currentTopicId = null,
        rootMessage = rootMessage,
        isGroup = false,
        isChannel = false,
        isAdmin = false,
        permissions = ChatPermissionsModel(),
        otherUser = null,
        currentUser = null,
        typingAction = null,
        memberCount = 0,
        onlineCount = 0,
        topics = emptyList(),
        chatTitle = "Chat",
        chatAvatar = null,
        chatPersonalAvatar = null,
        chatEmojiStatus = null,
        isOnline = false,
        isVerified = false,
        isSponsor = false,
        isWhitelistedInAdBlock = false,
        isInstalledFromGooglePlay = true,
        isMuted = false,
        isSearchActive = searchActive,
        searchQuery = "",
        isMember = true,
        canDeleteChat = false,
        pinnedMessage = pinned,
        pinnedMessageCount = if (pinned != null) 1 else 0,
        isPinnedMessageHidden = hidden
    )

    private fun pinnedMessage(id: Long) = MessageModel(
        id = id,
        date = id.toInt(),
        isOutgoing = false,
        senderName = "sender",
        chatId = 1L,
        content = MessageContent.Text("text")
    )
}
