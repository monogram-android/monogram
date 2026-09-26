package org.monogram.feature.chats

import org.monogram.core.models.ARCHIVE_FOLDER_ID
import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.PeerId
import org.monogram.core.models.contains
import org.monogram.core.models.isHiddenInternalChat
import org.monogram.core.models.isMigratedServicePlaceholder

fun visibleChats(
    chats: List<Chat>,
    folders: List<Folder>,
    selectedFolderId: Int?,
): List<Chat> {
    val available = chats.filterNot {
        it.left || it.isMigratedServicePlaceholder() || isHiddenInternalChat(it.id.value)
    }
    return when (selectedFolderId) {
        null, 0 -> available.filter { !it.archived }
        ARCHIVE_FOLDER_ID -> available.filter { it.archived }
        else -> {
            val folder = folders.find { it.id == selectedFolderId } ?: return available.filter { !it.archived }
            orderedFolderChats(
                available.filter { folder.contains(it) },
                folder,
            )
        }
    }
}

fun unreadChatIds(chats: List<Chat>, archive: Boolean): List<PeerId> =
    chats.filter { it.archived == archive && it.unreadCount > 0 }.map { it.id }

data class FolderUnreadBadge(
    val unmuted: Int,
    val muted: Int,
)

/** Folder chips count unread chats, not summed message counters. */
fun folderUnreadBadge(chats: List<Chat>): FolderUnreadBadge {
    var unmuted = 0
    var muted = 0
    for (chat in chats) {
        if (chat.unreadCount <= 0 && !chat.unreadMark && chat.unreadMentionsCount <= 0 && chat.unreadReactionsCount <= 0) continue
        if (chat.muted && chat.unreadMentionsCount <= 0 && chat.unreadReactionsCount <= 0) muted++ else unmuted++
    }
    return FolderUnreadBadge(unmuted = unmuted, muted = muted)
}

fun orderedFolderChats(chats: List<Chat>, folder: Folder): List<Chat> {
    val pinOrder = folderPinOrder(folder)
    // Main-list pin state is not part of a custom folder. Rebuild it even when
    // the folder has no pins so a folder is ordered only by its own pins and dates.
    val rows = chats.map { chat ->
        val order = pinOrder[chat.id.value]
        val pinned = order != null
        if (chat.pinned == pinned && (order == null || chat.pinnedOrder == order)) {
            chat
        } else {
            chat.copy(pinned = pinned, pinnedOrder = order ?: Int.MAX_VALUE)
        }
    }
    return rows.sortedWith(
        compareByDescending<Chat> { it.pinned }
            .thenBy { it.pinnedOrder }
            .thenByDescending { it.lastMessageDate ?: 0L }
            .thenByDescending { it.lastMessageId },
    )
}

private fun folderPinOrder(folder: Folder): Map<Long, Int> {
    if (folder.pinnedChatIds.isEmpty()) return emptyMap()
    val out = LinkedHashMap<Long, Int>(folder.pinnedChatIds.size)
    for (id in folder.pinnedChatIds) {
        out.putIfAbsent(id.value, out.size)
    }
    return out
}

const val DIALOGS_NETWORK_PAGE = 40
const val DIALOGS_PAINT_LIMIT = 12
const val ARCHIVE_PAINT_LIMIT = 3

fun paintDialogsWindow(
    chats: List<Chat>,
    limit: Int = DIALOGS_PAINT_LIMIT,
    archiveLimit: Int = ARCHIVE_PAINT_LIMIT,
): Pair<List<Chat>, List<Chat>> {
    val shownMain = sortChats(chats).filter { it.isMainListRow() }
    val archived = chats.filter { it.isArchiveListRow() }
        .sortedWith(compareByDescending { it.lastMessageDate ?: 0L })
    val left = chats.filter { !it.isShownInChatList() }
    val paintMain = shownMain.take(limit)
    val paintArchived = archived.take(archiveLimit.coerceAtLeast(0))
    val tail = shownMain.drop(paintMain.size) + archived.drop(paintArchived.size) + left
    return (paintMain + paintArchived) to tail
}

/** `messages.getDialogs` folder ids: 0 is the main list, 1 is the archive. */
const val MAIN_FOLDER_WIRE_ID = 0
const val ARCHIVE_FOLDER_WIRE_ID = 1

/**
 * Main-stream pages fetched per gesture while a custom folder is open. Custom filter ids are
 * rejected by `messages.getDialogs` (`FOLDER_ID_INVALID`), so the folder is filled by paging the
 * main stream and filtering locally; several pages per request keep a large folder from stalling.
 */
const val CUSTOM_FOLDER_PAGES = 3

/** Cursor state for one folder's dialog stream (the main list is tracked separately). */
data class FolderPaging(
    var page: List<Chat> = emptyList(),
    var started: Boolean = false,
    var hasMore: Boolean = true,
)

fun dialogsPageOffset(chat: Chat): Triple<Int, Int, Long>? {
    val date = chat.lastMessageDate?.toInt() ?: return null
    if (date <= 0 || chat.lastMessageId <= 0 || chat.id.value == 0L) return null
    return Triple(date, chat.lastMessageId, chat.id.value)
}

fun dialogsPageCursor(
    page: List<Chat>,
    skipPeerIds: Set<Long> = emptySet(),
    skipArchived: Boolean = true,
): Triple<Int, Int, Long>? {
    for (chat in page.asReversed()) {
        if (skipArchived && chat.archived) continue
        if (chat.id.value in skipPeerIds) continue
        dialogsPageOffset(chat)?.let { return it }
    }
    return null
}

fun dialogsHasMore(pageSize: Int, limit: Int = DIALOGS_NETWORK_PAGE): Boolean =
    pageSize >= limit

fun shouldPageChats(
    lastVisibleIndex: Int,
    size: Int,
    hasMore: Boolean,
    loadingMore: Boolean,
    leadingItems: Int = 0,
): Boolean = hasMore && !loadingMore && size > 0 &&
    lastVisibleIndex >= leadingItems + size - 4

fun archiveFolder(title: String): Folder = Folder(
    id = ARCHIVE_FOLDER_ID,
    title = title,
    excludeArchived = false,
)

fun chatListTabFolders(folders: List<Folder>): List<Folder> =
    folders.filter { it.id != 0 && it.id != ARCHIVE_FOLDER_ID }

fun defaultFolderId(folders: List<Folder>, showAllChats: Boolean = true): Int? {
    val tabs = folders.filter { it.id != ARCHIVE_FOLDER_ID }
    if (!showAllChats) {
        val nonAll = tabs.filter { it.id != 0 }
        return nonAll.firstOrNull()?.id
    }
    val first = tabs.firstOrNull() ?: return null
    if (tabs.none { it.id == 0 }) return null
    return first.id.takeUnless { it == 0 }
}

/** The error type a server returns when it keeps no dialog stream for a folder id. */
const val FOLDER_ID_INVALID = "FOLDER_ID_INVALID"
