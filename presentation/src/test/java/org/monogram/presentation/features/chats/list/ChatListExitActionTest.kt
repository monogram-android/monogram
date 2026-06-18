package org.monogram.presentation.features.chats.list

import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.domain.models.ChatModel
import org.monogram.presentation.features.chats.common.ChatExitAction

class ChatListExitActionTest {
    @Test
    fun `single private chat resolves to delete`() {
        assertEquals(
            ChatExitAction.Delete,
            resolveChatListExitAction(chat(canDelete = true))
        )
    }

    @Test
    fun `member group resolves to leave`() {
        assertEquals(
            ChatExitAction.Leave,
            resolveChatListExitAction(chat(isGroup = true, isMember = true))
        )
    }

    @Test
    fun `non member channel resolves to none`() {
        assertEquals(
            ChatExitAction.None,
            resolveChatListExitAction(chat(isChannel = true, isMember = false))
        )
    }

    private fun chat(
        isGroup: Boolean = false,
        isChannel: Boolean = false,
        isMember: Boolean = true,
        canDelete: Boolean = false
    ): ChatModel = ChatModel(
        id = 1L,
        title = "chat",
        unreadCount = 0,
        isGroup = isGroup,
        isChannel = isChannel,
        isMember = isMember,
        canBeDeletedOnlyForSelf = canDelete
    )
}
