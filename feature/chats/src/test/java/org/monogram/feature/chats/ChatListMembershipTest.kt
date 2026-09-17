package org.monogram.feature.chats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.Chat
import org.monogram.core.models.PeerId
import org.monogram.feature.chats.ChatsStoreTestFixtures.chat

class ChatListMembershipTest {
    @Test
    fun commentGroupTheAccountIsNotInIsHidden() {
        val comments = chat(
            id = 500,
            title = "Channel comments",
            isGroup = true,
            left = true,
        )
        assertFalse(comments.isShownInChatList())
        assertTrue(sortChats(listOf(comments, chat(id = 1, title = "Anna"))).none { it.id.value == 500L })
    }

    @Test
    fun groupsAndChannelsTheAccountIsInStayVisible() {
        val group = chat(id = 10, title = "Team", isGroup = true)
        val channel = chat(id = 11, title = "News", isChannel = true, isGroup = false)
        val forum = chat(id = 12, title = "Forum", isGroup = true, isForum = true)
        val sorted = sortChats(listOf(group, channel, forum))
        assertEquals(listOf(10L, 11L, 12L), sorted.map { it.id.value })
    }

    @Test
    fun leftDialogIsDroppedFromAppendedPagesToo() {
        val existing = listOf(chat(id = 1, title = "Anna"))
        val page = listOf(
            chat(id = 2, title = "Old group", isGroup = true, left = true),
            chat(id = 3, title = "Work", isGroup = true),
        )
        val merged = mergeChats(existing, page)
        assertEquals(listOf(1L, 3L), merged.map { it.id.value }.sorted())
    }

    @Test
    fun leavingADialogEvictsTheListedRow() {
        val listed = listOf(chat(id = 7, title = "Team", isGroup = true))
        val leftPage = listOf(chat(id = 7, title = "Team", isGroup = true, left = true))
        assertTrue(mergeChats(listed, leftPage).isEmpty())
    }

    @Test
    fun nothingIsHiddenWhenTheFlagIsAbsent() {
        val unknown = chat(id = 42, title = "Unknown")
        assertTrue(unknown.isShownInChatList())
    }

    @Test
    fun incomingMessageDoesNotPromoteAnArchivedOrLeftDialog() {
        val archived = chat(id = 8, title = "Old", lastMessageDate = 1).copy(archived = true)
        val comments = chat(id = 9, title = "Comments", isGroup = true, left = true)
        val listed = listOf(chat(id = 1, title = "Anna", lastMessageDate = 5))
        val incomingArchived = org.monogram.core.models.Message(
            id = org.monogram.core.models.MessageId(archived.id, 4),
            senderId = null,
            text = "hi",
            date = 9L,
            outgoing = false,
        )
        val (paint, tail) = paintDialogsWindow(
            applyIncomingMessage(listed + archived + comments, incomingArchived),
            limit = 12,
        )
        assertTrue(paint.none { it.id.value == 8L && !it.archived })
        assertTrue((paint + tail).any { it.id.value == 8L && it.archived })
        assertTrue(paint.none { it.id.value == 9L })
        assertTrue(visibleChats(paint, emptyList(), null).none { it.archived || it.left })
    }
}

internal object ChatsStoreTestFixtures {
    fun chat(
        id: Long,
        title: String,
        isGroup: Boolean = false,
        isChannel: Boolean = false,
        isForum: Boolean = false,
        left: Boolean = false,
        lastMessageDate: Long? = null,
    ) = Chat(
        id = PeerId(id),
        title = title,
        isGroup = isGroup,
        isChannel = isChannel,
        isForum = isForum,
        left = left,
        lastMessageDate = lastMessageDate,
    )
}
