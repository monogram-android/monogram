package org.monogram.feature.chats

import org.monogram.core.models.ARCHIVE_FOLDER_ID
import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.contains
import org.monogram.core.models.isHiddenInternalChat
import org.monogram.core.models.isMigratedServicePlaceholder
import org.monogram.core.ui.components.FolderChipItem

/**
 * Folder filter chips follow [folders] order. `id == 0` (`dialogFilterDefault`) is All chats;
 * if that placeholder is missing, All chats stays first. Badges count unread chats (never totals)
 * and stay off for the all-chats chip and for unread-only filters, whose label already says what
 * they are.
 */
fun folderChipItems(
    chats: List<Chat>,
    folders: List<Folder>,
    allChatsLabel: String,
    showMutedCounter: Boolean,
    showAllChats: Boolean = true,
): List<FolderChipItem> {
    val all = FolderChipItem(id = null, label = allChatsLabel, isAll = true)
    val available = chats.filterNot {
        it.isMigratedServicePlaceholder() || isHiddenInternalChat(it.id.value)
    }
    fun chip(folder: Folder): FolderChipItem {
        var unmuted = 0
        var muted = 0
        for (chat in available) {
            val hasUnread = chat.unreadCount > 0 || chat.unreadMark || chat.unreadMentionsCount > 0 || chat.unreadReactionsCount > 0
            if (!hasUnread || !folder.contains(chat)) continue
            if (chat.muted && chat.unreadMentionsCount <= 0 && chat.unreadReactionsCount <= 0) muted++ else unmuted++
        }
        return FolderChipItem(
            id = folder.id,
            label = folder.label,
            unread = unmuted,
            mutedUnread = if (showMutedCounter) muted else 0,
            isUnreadFilter = folder.excludeRead,
        )
    }
    val chips = folders.mapNotNull { folder ->
        when (folder.id) {
            ARCHIVE_FOLDER_ID -> null
            0 -> if (showAllChats) all else null
            else -> chip(folder)
        }
    }
    return when {
        !showAllChats -> chips
        chips.any { it.isAll } -> chips
        else -> listOf(all) + chips
    }
}
