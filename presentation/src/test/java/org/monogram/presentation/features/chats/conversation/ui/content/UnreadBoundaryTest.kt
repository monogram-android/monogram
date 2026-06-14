package org.monogram.presentation.features.chats.conversation.ui.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel

class UnreadBoundaryTest {
    @Test
    fun `boundary is first incoming message after last read id`() {
        val messages = listOf(
            message(id = 102L),
            message(id = 101L),
            message(id = 100L, isRead = true)
        )
        val groupedItems = groupMessagesByAlbum(messages)

        val boundary = findFirstUnreadBoundary(
            messages = messages,
            groupedItems = groupedItems,
            lastReadInboxMessageId = 100L
        )

        assertEquals(101L, boundary?.firstMessageId)
    }

    @Test
    fun `boundary returns album group when first unread is inside album`() {
        val messages = listOf(
            message(id = 103L, albumId = 7L),
            message(id = 102L, albumId = 7L),
            message(id = 101L, isRead = true)
        )
        val groupedItems = groupMessagesByAlbum(messages)

        val boundary = findFirstUnreadBoundary(
            messages = messages,
            groupedItems = groupedItems,
            lastReadInboxMessageId = 101L
        )

        assertTrue(boundary is GroupedMessageItem.Album)
        assertEquals(103L, boundary?.firstMessageId)
    }

    @Test
    fun `boundary is absent when first unread is not loaded`() {
        val messages = listOf(
            message(id = 100L, isRead = true),
            message(id = 99L, isRead = true)
        )
        val groupedItems = groupMessagesByAlbum(messages)

        val boundary = findFirstUnreadBoundary(
            messages = messages,
            groupedItems = groupedItems,
            lastReadInboxMessageId = 100L
        )

        assertNull(boundary)
    }

    private fun message(
        id: Long,
        albumId: Long = 0L,
        isRead: Boolean = false
    ): MessageModel =
        MessageModel(
            id = id,
            date = id.toInt(),
            isOutgoing = false,
            senderName = "sender",
            chatId = 1L,
            content = MessageContent.Text("text"),
            mediaAlbumId = albumId,
            isRead = isRead
        )
}
