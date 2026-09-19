package org.monogram.feature.chats.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.Chat
import org.monogram.core.models.PeerId

class ChatListSnapshotTest {
    @Test
    fun replaceKeepsIdsWhenOnlyOneRowChanges() {
        val snapshot = ChatListSnapshot()
        val first = Chat(id = PeerId(1), title = "Ada", typing = false)
        val second = Chat(id = PeerId(2), title = "Bob")
        snapshot.replace(listOf(first, second))
        val ids = snapshot.ids
        snapshot.replace(listOf(first.copy(typing = true), second))
        assertSame(ids, snapshot.ids)
        assertEquals(true, snapshot.chat(1)?.typing)
        assertEquals(second, snapshot.chat(2))
        assertEquals(0, snapshot.unmutedUnread)
    }

    @Test
    fun replaceDropsRemovedIdsAndRecountsUnread() {
        val snapshot = ChatListSnapshot()
        snapshot.replace(
            listOf(
                Chat(id = PeerId(1), title = "Ada", unreadCount = 2),
                Chat(id = PeerId(2), title = "Bob", unreadCount = 1, muted = true),
            ),
        )
        assertEquals(1, snapshot.unmutedUnread)
        snapshot.replace(listOf(Chat(id = PeerId(2), title = "Bob", unreadCount = 3)))
        assertEquals(listOf(2L), snapshot.ids)
        assertTrue(snapshot.chat(1) == null)
        assertEquals(1, snapshot.unmutedUnread)
    }
}
