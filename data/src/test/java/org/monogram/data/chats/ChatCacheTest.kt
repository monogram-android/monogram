package org.monogram.data.chats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.monogram.data.db.model.ChatEntity

class ChatCacheTest {

    @Test
    fun `putChatFromEntity keeps lastMessage null for empty cached preview`() {
        val cache = ChatCache()
        val entity = ChatEntity(
            id = 1L,
            title = "Empty chat",
            unreadCount = 0,
            avatarPath = null,
            lastMessageText = "",
            lastMessageTime = "0",
            order = 1L,
            isPinned = false,
            isMuted = false,
            isChannel = false,
            isGroup = false,
            type = "PRIVATE",
            isArchived = false,
            memberCount = 0,
            onlineCount = 0
        )

        cache.putChatFromEntity(entity)

        val chat = cache.getChat(1L)
        assertNull(chat?.lastMessage)
    }

    @Test
    fun `putChatFromEntity restores lastMessage when cached preview is meaningful`() {
        val cache = ChatCache()
        val entity = ChatEntity(
            id = 2L,
            title = "Photo chat",
            unreadCount = 0,
            avatarPath = null,
            lastMessageText = "caption",
            lastMessageTime = "1710000000",
            lastMessageDate = 1710000000,
            lastMessageId = 99L,
            lastMessageContentType = "photo",
            order = 2L,
            isPinned = false,
            isMuted = false,
            isChannel = false,
            isGroup = false,
            type = "PRIVATE",
            isArchived = false,
            memberCount = 0,
            onlineCount = 0
        )

        cache.putChatFromEntity(entity)

        val chat = cache.getChat(2L)
        assertEquals(99L, chat?.lastMessage?.id)
        assertEquals(1710000000, chat?.lastMessage?.date)
    }
}
