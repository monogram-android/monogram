package org.monogram.feature.chats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.PeerId
import org.monogram.core.ui.components.FolderChips
import org.monogram.core.ui.components.FolderChipItem

class FolderChipsTest {
    private fun chat(
        id: Long,
        unread: Int = 0,
        muted: Boolean = false,
        archived: Boolean = false,
        isGroup: Boolean = false,
    ) = Chat(
        id = PeerId(id),
        title = "c$id",
        unreadCount = unread,
        muted = muted,
        archived = archived,
        isGroup = isGroup,
        lastMessageDate = id,
    )

    @Test
    fun allChatsChipHasNoBadgeAndComesFirst() {
        val chips = folderChipItems(
            chats = listOf(chat(1, unread = 3), chat(2, unread = 1, muted = true)),
            folders = emptyList(),
            allChatsLabel = "All chats",
            showMutedCounter = true,
        )
        assertEquals(1, chips.size)
        assertTrue(chips.single().isAll)
        assertFalse(chips.single().showsBadge)
        assertEquals("All chats", chips.single().label)
    }

    @Test
    fun folderBadgesCountUnreadChatsNotMessages() {
        val work = Folder(id = 7, title = "Work", includeGroups = true)
        val chips = folderChipItems(
            chats = listOf(
                chat(1, unread = 12, isGroup = true),
                chat(2, unread = 2, isGroup = true),
                chat(3, unread = 0, isGroup = true),
            ),
            folders = listOf(work),
            allChatsLabel = "All chats",
            showMutedCounter = true,
        )
        val chip = chips.last()
        assertEquals(7, chip.id)
        assertEquals(2, chip.unread)
        assertEquals(0, chip.mutedUnread)
        assertTrue(chip.showsBadge)
    }

    @Test
    fun mutedCounterRespectsTheAppearanceSetting() {
        val work = Folder(id = 7, title = "Work", includeGroups = true)
        val chats = listOf(
            chat(1, unread = 4, muted = true, isGroup = true),
            chat(2, unread = 1, isGroup = true),
        )
        val hidden = folderChipItems(chats, listOf(work), "All", showMutedCounter = false).last()
        assertEquals(1, hidden.unread)
        assertEquals(0, hidden.mutedUnread)
        val shown = folderChipItems(chats, listOf(work), "All", showMutedCounter = true).last()
        assertEquals(1, shown.unread)
        assertEquals(1, shown.mutedUnread)
    }

    @Test
    fun unreadOnlyFoldersAreMarkedAndHideTheirBadge() {
        val unreadFilter = Folder(id = 9, title = "Unread", excludeRead = true, includeGroups = true)
        val chip = folderChipItems(
            chats = listOf(chat(1, unread = 5, isGroup = true)),
            folders = listOf(unreadFilter),
            allChatsLabel = "All",
            showMutedCounter = true,
        ).last()
        assertTrue(chip.isUnreadFilter)
        assertFalse(chip.showsBadge)
    }

    @Test
    fun archiveFolderIsNotAChatListChip() {
        val chips = folderChipItems(
            chats = emptyList(),
            folders = listOf(archiveFolder("Archive"), Folder(id = 0, title = "All")),
            allChatsLabel = "All chats",
            showMutedCounter = false,
        )
        assertEquals(listOf("All chats"), chips.map { it.label })
    }

    @Test
    fun rebuiltChipListComparesEqualToAnEqualOne() {
        fun build(secondChatUnread: Boolean) = FolderChips(
            folderChipItems(
                chats = listOf(
                    chat(1, unread = 3, isGroup = true),
                    chat(2, unread = if (secondChatUnread) 2 else 0, isGroup = true),
                ),
                folders = listOf(Folder(id = 7, title = "Work", includeGroups = true)),
                allChatsLabel = "All chats",
                showMutedCounter = true,
            ),
        )

        assertEquals(build(secondChatUnread = true), build(secondChatUnread = true))
        assertNotEquals(build(secondChatUnread = true), build(secondChatUnread = false))
    }
}
