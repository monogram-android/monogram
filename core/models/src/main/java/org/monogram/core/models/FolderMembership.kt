package org.monogram.core.models

const val ARCHIVE_FOLDER_ID = -1

/**
 * Folder membership, shared by the chat-list folder chips and the folders screen so both report the
 * same thing. [Folder.chatIds] is pinned peers plus explicitly included peers. A rule-based folder
 * (groups/contacts/unread-only) can have an empty [Folder.chatIds] and still contain many chats.
 * [Folder.pinnedChatIds] is only `dialogFilter.pinned_peers`.
 */
fun Folder.contains(chat: Chat): Boolean {
    if (id == ARCHIVE_FOLDER_ID) return chat.archived
    if (excludeChatIds.contains(chat.id)) return false
    if (chatIds.contains(chat.id)) return true
    if (excludeArchived && chat.archived) return false
    if (excludeMuted && chat.muted && chat.unreadMentionsCount == 0 && chat.unreadReactionsCount == 0) return false
    if (excludeRead && chat.unreadCount <= 0 && !chat.unreadMark && chat.unreadMentionsCount == 0 && chat.unreadReactionsCount == 0) return false
    if (chatIds.isEmpty() &&
        !includeContacts &&
        !includeNonContacts &&
        !includeGroups &&
        !includeChannels &&
        !includeBots
    ) {
        return false
    }
    val matchesKind = when {
        chat.isChannel && includeChannels -> true
        chat.isGroup && includeGroups -> true
        chat.isBot && includeBots -> true
        !chat.isChannel && !chat.isGroup && chat.isContact && includeContacts -> true
        !chat.isChannel && !chat.isGroup && !chat.isContact && !chat.isBot && includeNonContacts -> true
        else -> false
    }
    return matchesKind
}
