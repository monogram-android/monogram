package org.monogram.feature.chats

import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.ARCHIVE_FOLDER_ID
import org.monogram.core.models.Chat
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.core.models.SearchPeer
import org.monogram.core.models.displayPreview
import org.monogram.core.models.peerAvatarCacheKey

/** Telegram Android DialogsSearchAdapter uses 20 and 300ms. Limit stays ≤ 100. */
internal const val GLOBAL_SEARCH_LIMIT = 20
internal const val GLOBAL_SEARCH_DEBOUNCE_MS = 300L

internal fun searchFolderId(activeFolderId: Int?): Int =
    if (activeFolderId == ARCHIVE_FOLDER_ID) ARCHIVE_FOLDER_WIRE_ID else MAIN_FOLDER_WIRE_ID

internal fun Chat.matchesSearchQuery(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return false
    return title.contains(q, ignoreCase = true) ||
        displayPreview().contains(q, ignoreCase = true)
}

/** Only peers already shown in the local section, so username hits stay in people/chats. */
internal fun localSearchMatchIds(chats: List<Chat>, query: String): Set<Long> =
    chats.filter { it.matchesSearchQuery(query) }.map { it.id.value }.toSet()

internal fun excludeKnownPeers(peers: List<SearchPeer>, knownIds: Set<Long>): List<SearchPeer> =
    peers.filter { it.id.value !in knownIds }

internal fun SearchPeer.toChat(): Chat = Chat(
    id = id,
    title = title,
    isChannel = isChannel,
    isGroup = isGroup,
    isBot = isBot,
    lastMessagePreview = username?.takeIf { it.isNotBlank() }?.let { "@$it" },
    photoCacheKey = peerAvatarCacheKey(id),
)

internal fun Message.toSearchChat(title: String): Chat = Chat(
    id = id.chatId,
    title = title.ifBlank { id.chatId.value.toString() },
    lastMessagePreview = text,
    lastMessageDate = date,
    lastMessageId = id.id,
    lastMessageOutgoing = outgoing,
    lastMessageMediaKind = mediaKind,
    lastMediaThumbCacheKey = thumbCacheKey,
    lastMessageSenderName = senderName,
)

internal fun titleForSearchMessage(
    message: Message,
    chats: List<Chat>,
    people: List<SearchPeer>,
    foundChats: List<SearchPeer>,
): String =
    chats.firstOrNull { it.id == message.id.chatId }?.title?.takeIf { it.isNotBlank() }
        ?: people.firstOrNull { it.id == message.id.chatId }?.title?.takeIf { it.isNotBlank() }
        ?: foundChats.firstOrNull { it.id == message.id.chatId }?.title?.takeIf { it.isNotBlank() }
        ?: message.senderName?.takeIf { it.isNotBlank() }
        ?: ""

internal fun isEmptySearchQuery(error: TelegramError): Boolean =
    error.type == "SEARCH_QUERY_EMPTY"

/** Same jump as notification open: chat id + message id. */
internal fun searchMessageJump(message: Message): Pair<PeerId, Int> =
    message.id.chatId to message.id.id

internal fun globalSearchHasMore(pageSize: Int, nextRate: Int, nextPeerId: Long, nextOffsetId: Int): Boolean =
    pageSize >= GLOBAL_SEARCH_LIMIT && (nextRate != 0 || nextPeerId != 0L || nextOffsetId != 0)
