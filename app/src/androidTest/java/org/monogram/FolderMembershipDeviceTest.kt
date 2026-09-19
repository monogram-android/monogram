package org.monogram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.PeerId
import org.monogram.feature.chats.FolderUnreadBadge
import org.monogram.feature.chats.folderUnreadBadge
import org.monogram.feature.chats.visibleChats

class FolderMembershipDeviceTest {
    @Test
    fun visibleChatsKeepsExplicitArchivedMembershipAndFolderOrdering() {
        val rows = (1L..48L).map { id ->
            Chat(
                id = PeerId(id),
                title = "Chat $id",
                lastMessageDate = id,
                pinned = id == 1L,
                unreadCount = if (id == 48L) 2 else 0,
            )
        } + listOf(
            Chat(
                id = PeerId(98),
                title = "Archived rule member",
                archived = true,
                isGroup = true,
                lastMessageDate = 98,
                unreadCount = 2,
            ),
            Chat(
                id = PeerId(99),
                title = "Archived late member",
                archived = true,
                lastMessageDate = 99,
                unreadCount = 3,
            ),
            Chat(
                id = PeerId(97),
                title = "Recent group",
                isGroup = true,
                lastMessageDate = 97,
                pinned = true,
            ),
        )
        val folder = Folder(
            id = 7,
            title = "Work",
            chatIds = listOf(PeerId(99)),
            includeGroups = true,
            excludeArchived = false,
        )

        val visible = visibleChats(rows, listOf(folder), 7)
        assertEquals(listOf(99L, 98L, 97L), visible.map { it.id.value })
        assertTrue(visible[0].archived)
        assertEquals(FolderUnreadBadge(unmuted = 2, muted = 0), folderUnreadBadge(visible))
    }
}
