package org.monogram.feature.chats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.PeerId
import org.monogram.core.models.ARCHIVE_FOLDER_ID
import org.monogram.core.models.contains

class FolderMembershipTest {
    private fun chat(
        id: Long,
        title: String = "c$id",
        pinned: Boolean = false,
        archived: Boolean = false,
        muted: Boolean = false,
        unread: Int = 0,
        date: Long = id,
        isGroup: Boolean = false,
        isChannel: Boolean = false,
        isContact: Boolean = false,
        isBot: Boolean = false,
    ) = Chat(
        id = PeerId(id),
        title = title,
        isChannel = isChannel,
        isGroup = isGroup,
        unreadCount = unread,
        lastMessageDate = date,
        archived = archived,
        muted = muted,
        isContact = isContact,
        isBot = isBot,
        pinned = pinned,
    )

    @Test
    fun archiveFolderOnlyShowsArchived() {
        val chats = listOf(chat(1), chat(2, archived = true))
        val shown = visibleChats(chats, emptyList(), ARCHIVE_FOLDER_ID)
        assertEquals(listOf(2L), shown.map { it.id.value })
    }

    @Test
    fun allTabHidesArchived() {
        val chats = listOf(chat(1), chat(2, archived = true))
        val shown = visibleChats(chats, emptyList(), null)
        assertEquals(listOf(1L), shown.map { it.id.value })
        val allChip = visibleChats(chats, listOf(Folder(id = 0, title = "All")), 0)
        assertEquals(listOf(1L), allChip.map { it.id.value })
    }

    @Test
    fun chatListTabsOmitArchiveAndAllChats() {
        val tabs = chatListTabFolders(
            listOf(
                archiveFolder("Archive"),
                Folder(id = 0, title = "All"),
                Folder(id = 7, title = "Work"),
            ),
        )
        assertEquals(listOf(7), tabs.map { it.id })
    }

    @Test
    fun defaultFolderIsAllChatsWhenItComesFirst() {
        val folders = listOf(
            Folder(id = 0, title = "All"),
            Folder(id = 7, title = "Work"),
            Folder(id = 3, title = "Fun"),
        )
        assertEquals(null, defaultFolderId(folders))
    }

    @Test
    fun defaultFolderFollowsTheStoredArrangement() {
        assertEquals(
            3,
            defaultFolderId(
                listOf(
                    Folder(id = 3, title = "Fun"),
                    Folder(id = 0, title = "All"),
                    Folder(id = 7, title = "Work"),
                ),
            ),
        )
    }

    @Test
    fun defaultFolderSkipsAllChatsWhereverItSits() {
        // All chats is movable for Premium users, so it cannot be assumed first.
        assertEquals(
            7,
            defaultFolderId(listOf(Folder(id = 7, title = "Work"), Folder(id = 0, title = "All"))),
        )
    }

    @Test
    fun noDefaultFolderWithoutChips() {
        assertEquals(null, defaultFolderId(emptyList()))
        assertEquals(null, defaultFolderId(listOf(archiveFolder("Archive"), Folder(id = 0, title = "All"))))
    }

    @Test
    fun defaultFolderWhenAllChatsHiddenSelectsFirstCustomFolder() {
        val folders = listOf(
            Folder(id = 0, title = "All"),
            Folder(id = 7, title = "Work"),
            Folder(id = 3, title = "Fun"),
        )
        assertEquals(7, defaultFolderId(folders, showAllChats = false))
    }

    @Test
    fun allTabHidesMigratedServicePlaceholder() {
        val migrated = chat(2, title = "forum").copy(
            isGroup = true,
            lastMessagePreview = "migrate_to\u001F\u001F",
            lastMessageMediaKind = "service",
        )
        assertEquals(listOf(1L), visibleChats(listOf(chat(1), migrated), emptyList(), null).map { it.id.value })
    }

    @Test
    fun explicitIncludesOverrideRuleExclusionsButNotNeverShow() {
        val folder = Folder(
            id = 7,
            title = "Work",
            chatIds = listOf(PeerId(1), PeerId(3), PeerId(4), PeerId(5)),
            excludeChatIds = listOf(PeerId(2)),
            excludeArchived = true,
            excludeMuted = true,
            excludeRead = true,
        )
        assertTrue(folder.contains(chat(1)))
        assertFalse(folder.contains(chat(2)))
        assertTrue(folder.contains(chat(3, muted = true)))
        assertTrue(folder.contains(chat(4, archived = true)))
        assertTrue(folder.contains(chat(5)))
    }

    @Test
    fun visibleCustomFolderHonorsArchiveRulesThroughPublicPath() {
        val archivedExplicit = chat(1, archived = true)
        val archivedRuleMatch = chat(2, archived = true, isGroup = true)
        val folder = Folder(
            id = 7,
            title = "Work",
            chatIds = listOf(archivedExplicit.id),
            includeGroups = true,
            excludeArchived = true,
        )

        assertEquals(
            listOf(1L),
            visibleChats(listOf(archivedExplicit, archivedRuleMatch), listOf(folder), 7)
                .map { it.id.value },
        )

        val archiveAllowed = folder.copy(excludeArchived = false)
        assertEquals(
            listOf(2L, 1L),
            visibleChats(listOf(archivedExplicit, archivedRuleMatch), listOf(archiveAllowed), 7)
                .map { it.id.value },
        )
    }

    @Test
    fun folderBadgeZeroWhenNothingUnread() {
        val badge = folderUnreadBadge(listOf(chat(1), chat(2)))
        assertEquals(0, badge.unmuted)
        assertEquals(0, badge.muted)
    }

    @Test
    fun folderBadgeCountsUnreadChatsNotMessages() {
        val chats = listOf(
            chat(1, unread = 400),
            chat(2, unread = 50),
            chat(3, unread = 0),
            chat(4, unread = 9, muted = true),
        )
        val badge = folderUnreadBadge(chats)
        assertEquals(2, badge.unmuted)
        assertEquals(1, badge.muted)
    }

    @Test
    fun folderBadgeCountsMutedSeparatelyFromEnabled() {
        val chats = listOf(
            chat(1, unread = 12, muted = true),
            chat(2, unread = 3, muted = true),
            chat(3, unread = 0),
            chat(4, unread = 1),
        )
        val badge = folderUnreadBadge(chats)
        assertEquals(1, badge.unmuted)
        assertEquals(2, badge.muted)
    }

    @Test
    fun dialogsPageOffsetUsesTopMessageNotZero() {
        val row = chat(9, date = 1_700_000_123)
            .copy(lastMessageId = 44)
        val offset = dialogsPageOffset(row)
        assertEquals(Triple(1_700_000_123, 44, 9L), offset)
    }

    @Test
    fun dialogsPageOffsetNullWithoutMessage() {
        assertEquals(null, dialogsPageOffset(chat(1, date = 0).copy(lastMessageId = 0)))
    }

    @Test
    fun dialogsPageCursorSkipsArchivedAndFailedPeers() {
        val page = listOf(
            chat(1, date = 300).copy(lastMessageId = 3),
            chat(2, date = 200, archived = true).copy(lastMessageId = 2),
            chat(3, date = 100).copy(lastMessageId = 1),
        )
        assertEquals(Triple(100, 1, 3L), dialogsPageCursor(page))
        assertEquals(Triple(300, 3, 1L), dialogsPageCursor(page, skipPeerIds = setOf(3L)))
        assertEquals(null, dialogsPageCursor(page, skipPeerIds = setOf(1L, 3L)))
    }

    @Test
    fun dialogsHasMoreMatchesNetworkLimit() {
        assertTrue(dialogsHasMore(40))
        assertFalse(dialogsHasMore(39))
        assertFalse(dialogsHasMore(0))
    }

    @Test
    fun folderPageCursorKeepsArchivedRows() {
        // A folder page may contain archived dialogs, so its cursor must not skip them.
        val page = listOf(
            chat(1, date = 300, archived = true).copy(lastMessageId = 3),
            chat(2, date = 200, archived = true).copy(lastMessageId = 2),
        )
        assertEquals(Triple(200, 2, 2L), dialogsPageCursor(page, skipArchived = false))
        assertEquals(null, dialogsPageCursor(page))
    }

    @Test
    fun folderPagingStartsUnseededAndOpenForMore() {
        val paging = FolderPaging()
        assertFalse(paging.started)
        assertTrue(paging.hasMore)
        assertTrue(paging.page.isEmpty())
        assertEquals(ARCHIVE_FOLDER_WIRE_ID, 1)
        assertEquals(MAIN_FOLDER_WIRE_ID, 0)
    }

    @Test
    fun unreadChatIdsSkipsReadAndTheOtherList() {
        val chats = listOf(
            chat(1, unread = 3),
            chat(2, unread = 0),
            chat(3, unread = 1, archived = true),
        )
        assertEquals(listOf(PeerId(1)), unreadChatIds(chats, archive = false))
        assertEquals(listOf(PeerId(3)), unreadChatIds(chats, archive = true))
    }

    @Test
    fun shouldPageChatsRespectsStartAndEnd() {
        assertFalse(shouldPageChats(0, size = 0, hasMore = true, loadingMore = false))
        assertFalse(shouldPageChats(20, size = 40, hasMore = false, loadingMore = false))
        assertFalse(shouldPageChats(38, size = 40, hasMore = true, loadingMore = true))
        assertTrue(shouldPageChats(38, size = 40, hasMore = true, loadingMore = false))
    }

    @Test
    fun paintDialogsWindowKeepsTopRows() {
        val chats = (1..20).map { chat(it.toLong(), date = it.toLong()) }
        val (paint, tail) = paintDialogsWindow(chats, limit = 12)
        assertEquals((20L downTo 9L).toList(), paint.map { it.id.value })
        assertEquals((8L downTo 1L).toList(), tail.map { it.id.value })
    }

    @Test
    fun paintDialogsWindowCanKeepTheFullArchive() {
        val chats = (1..10).map { chat(it.toLong(), date = it.toLong(), archived = true) } +
            chat(100, date = 100)
        val (paint, tail) = paintDialogsWindow(chats, limit = 12, archiveLimit = 10)
        assertEquals(10, paint.count { it.archived })
        assertTrue(tail.none { it.archived })
        assertEquals(listOf(100L), paint.filter { !it.archived }.map { it.id.value })
    }

    @Test
    fun paintDialogsWindowKeepsArchivedAndLeftOutOfTheMainWindow() {
        val chats = listOf(
            chat(1, date = 10),
            chat(2, date = 20, archived = true),
            chat(3, date = 30),
            chat(4, date = 40).copy(left = true, isGroup = true),
        )
        val (paint, tail) = paintDialogsWindow(chats, limit = 12)
        assertEquals(listOf(3L, 1L, 2L), paint.map { it.id.value })
        assertTrue(paint.take(2).none { it.archived || it.left })
        assertTrue(tail.any { it.id.value == 4L && it.left })
        assertTrue(visibleChats(paint + tail, emptyList(), null).none { it.left || it.archived })
    }

    @Test
    fun folderChatsSortByLastMessageDateNotIncludeList() {
        val folder = Folder(
            id = 9,
            title = "People",
            chatIds = listOf(PeerId(30), PeerId(10), PeerId(20)),
        )
        val chats = listOf(
            chat(10, date = 100).copy(lastMessageId = 2),
            chat(20, date = 200).copy(lastMessageId = 3),
            chat(30, date = 50).copy(lastMessageId = 1),
        )
        val shown = visibleChats(chats, listOf(folder), 9)
        assertEquals(listOf(20L, 10L, 30L), shown.map { it.id.value })
    }

    @Test
    fun folderChatsBreakDateTiesByMessageId() {
        val folder = Folder(
            id = 9,
            title = "People",
            chatIds = listOf(PeerId(1), PeerId(2)),
        )
        val chats = listOf(
            chat(1, date = 100).copy(lastMessageId = 4),
            chat(2, date = 100).copy(lastMessageId = 9),
        )
        val shown = visibleChats(chats, listOf(folder), 9)
        assertEquals(listOf(2L, 1L), shown.map { it.id.value })
    }

    @Test
    fun folderPinsStayAboveDateOrder() {
        val folder = Folder(
            id = 9,
            title = "People",
            chatIds = listOf(PeerId(10), PeerId(20), PeerId(30)),
            pinnedChatIds = listOf(PeerId(10), PeerId(30)),
        )
        val chats = listOf(
            chat(10, date = 50).copy(lastMessageId = 1),
            chat(20, date = 300).copy(lastMessageId = 3),
            chat(30, date = 40).copy(lastMessageId = 2),
        )
        val shown = visibleChats(chats, listOf(folder), 9)
        assertEquals(listOf(10L, 30L, 20L), shown.map { it.id.value })
        assertTrue(shown[0].pinned)
        assertTrue(shown[1].pinned)
        assertFalse(shown[2].pinned)
        assertEquals(0, shown[0].pinnedOrder)
        assertEquals(1, shown[1].pinnedOrder)
    }

    @Test
    fun includePeersAreNotPinnedWhenFolderHasPins() {
        val folder = Folder(
            id = 9,
            title = "People",
            chatIds = listOf(PeerId(1), PeerId(2)),
            pinnedChatIds = listOf(PeerId(1)),
        )
        val chats = listOf(
            chat(1, date = 10).copy(lastMessageId = 1),
            chat(2, date = 20, pinned = true).copy(pinnedOrder = 0, lastMessageId = 2),
        )
        val shown = visibleChats(chats, listOf(folder), 9)
        assertEquals(listOf(1L, 2L), shown.map { it.id.value })
        assertTrue(shown[0].pinned)
        assertFalse(shown[1].pinned)
    }

    @Test
    fun folderWithoutPinsIgnoresMainListPins() {
        val folder = Folder(
            id = 9,
            title = "People",
            chatIds = listOf(PeerId(1), PeerId(2), PeerId(3)),
        )
        val chats = listOf(
            chat(1, date = 10, pinned = true).copy(pinnedOrder = 1, lastMessageId = 1),
            chat(2, date = 30).copy(lastMessageId = 2),
            chat(3, date = 20, pinned = true).copy(pinnedOrder = 0, lastMessageId = 3),
        )
        val shown = visibleChats(chats, listOf(folder), 9)
        assertEquals(listOf(2L, 3L, 1L), shown.map { it.id.value })
        assertFalse(shown[0].pinned)
        assertFalse(shown[1].pinned)
        assertFalse(shown[2].pinned)
    }
}
