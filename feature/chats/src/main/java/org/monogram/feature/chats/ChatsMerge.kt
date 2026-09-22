package org.monogram.feature.chats

import org.monogram.core.database.dao.ChatReadState
import org.monogram.core.models.Chat
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.core.models.chatListPreviewSource
import org.monogram.core.models.isMigratedServicePlaceholder
import org.monogram.core.models.mergeLocalCache
import org.monogram.core.models.preferredPeerTitle

internal fun applyReadStates(chats: List<Chat>, rows: Map<Long, ChatReadState>): List<Chat> {
    // Room invalidates the whole table; apply its snapshot once, copying only on change.
    // A dialog can clear mentions/reactions while its normal read cursor stays unchanged.
    var updated: MutableList<Chat>? = null
    chats.forEachIndexed { index, chat ->
        val row = rows[chat.id.value] ?: return@forEachIndexed
        val acceptsReadState = row.readInboxMaxId >= chat.readInboxMaxId
        val unread = if (acceptsReadState) row.unreadCount.coerceAtLeast(0) else chat.unreadCount
        val mentions = row.unreadMentionsCount?.coerceAtLeast(0) ?: chat.unreadMentionsCount
        val reactions = row.unreadReactionsCount?.coerceAtLeast(0) ?: chat.unreadReactionsCount
        val readInboxMaxId = if (acceptsReadState) row.readInboxMaxId else chat.readInboxMaxId
        if (readInboxMaxId == chat.readInboxMaxId &&
            unread == chat.unreadCount &&
            mentions == chat.unreadMentionsCount &&
            reactions == chat.unreadReactionsCount
        ) return@forEachIndexed
        val target = updated ?: chats.toMutableList().also { updated = it }
        target[index] = chat.copy(
            readInboxMaxId = readInboxMaxId,
            unreadCount = unread,
            unreadMentionsCount = mentions,
            unreadReactionsCount = reactions,
        )
    }
    return updated ?: chats
}

internal fun Chat.isShownInChatList(): Boolean = !left && !isMigratedServicePlaceholder()

internal fun Chat.isMainListRow(): Boolean = isShownInChatList() && !archived

internal fun Chat.isArchiveListRow(): Boolean = isShownInChatList() && archived

internal fun sortChats(chats: Collection<Chat>): List<Chat> =
    chats.filter { it.isShownInChatList() }.sortedWith(
        compareByDescending<Chat> { it.pinned }
            .thenBy { it.pinnedOrder }
            .thenByDescending { it.lastMessageDate ?: 0L },
    )

internal fun mergeChats(current: List<Chat>, extra: List<Chat>): List<Chat> {
    val typing = current.filter { it.typing }.associateBy { it.id.value }
    val byId = LinkedHashMap<Long, Chat>()
    current.forEach { byId[it.id.value] = it }
    val dialogsPage = extra.size > 1
    extra.forEach { incoming ->
        if (!incoming.isShownInChatList()) {
            byId.remove(incoming.id.value)
            return@forEach
        }
        val prev = byId[incoming.id.value]
        val pinnedOrder = when {
            incoming.pinned && incoming.pinnedOrder != Int.MAX_VALUE -> incoming.pinnedOrder
            dialogsPage -> Int.MAX_VALUE
            incoming.pinned -> prev?.pinnedOrder ?: incoming.pinnedOrder
            else -> Int.MAX_VALUE
        }
        val live = typing[incoming.id.value]
        byId[incoming.id.value] = incoming.mergeLocalCache(prev).copy(
            title = preferredPeerTitle(incoming.title, prev?.title, incoming.id.value),
            typing = live != null,
            typingName = live?.typingName,
            typingAction = live?.typingAction,
            pinned = incoming.pinned,
            pinnedOrder = pinnedOrder,
            photoCacheKey = incoming.photoCacheKey ?: prev?.photoCacheKey,
            dialogScrollMessageId = incoming.dialogScrollMessageId ?: prev?.dialogScrollMessageId,
        )
    }
    return sortChats(byId.values)
}

/** Network `getDialogs` pin++ wins over a Room row that only had date order. */
internal fun withNetworkPinOrder(merged: List<Chat>, network: List<Chat>): List<Chat> {
    if (network.isEmpty() || merged.isEmpty()) return merged
    val pins = HashMap<Long, Pair<Boolean, Int>>(network.size)
    network.forEach { pins[it.id.value] = it.pinned to it.pinnedOrder }
    var changed: MutableList<Chat>? = null
    merged.forEachIndexed { index, chat ->
        val pin = pins[chat.id.value] ?: return@forEachIndexed
        if (chat.pinned == pin.first && chat.pinnedOrder == pin.second) return@forEachIndexed
        val target = changed ?: merged.toMutableList().also { changed = it }
        target[index] = chat.copy(pinned = pin.first, pinnedOrder = pin.second)
    }
    return changed ?: merged
}

internal fun applyEditedMessage(chats: List<Chat>, message: Message): List<Chat> =
    chats.map { chat ->
        if (chat.id == message.id.chatId && chat.lastMessageId == message.id.id) {
            chat.copy(
                lastMessagePreview = message.chatListPreviewSource(),
                lastMessageOutgoing = message.outgoing,
                lastMessageSenderName = message.senderName,
                lastMessageMediaKind = message.mediaKind,
                lastMessageDate = message.date,
                lastMediaThumbCacheKey = message.thumbCacheKey ?: chat.lastMediaThumbCacheKey,
            )
        } else {
            chat
        }
    }

internal fun applyLatestReplacement(
    chats: List<Chat>,
    chatId: PeerId,
    next: Message?,
): List<Chat> =
    chats.map { chat ->
        if (chat.id != chatId) {
            chat
        } else {
            chat.copy(
                lastMessagePreview = next?.chatListPreviewSource(),
                lastMessageOutgoing = next?.outgoing ?: false,
                lastMessageSenderName = next?.senderName,
                lastMessageMediaKind = next?.mediaKind,
                lastMessageDate = next?.date,
                lastMessageId = next?.id?.id ?: 0,
                lastMediaThumbCacheKey = next?.thumbCacheKey,
            )
        }
    }

internal fun applyUnreadMentions(chats: List<Chat>, chatId: PeerId, stillUnread: Int): List<Chat> {
    val still = stillUnread.coerceAtLeast(0)
    return chats.map { chat ->
        if (chat.id == chatId && chat.unreadMentionsCount != still) {
            chat.copy(unreadMentionsCount = still)
        } else {
            chat
        }
    }
}

internal fun applyUnreadReactions(chats: List<Chat>, chatId: PeerId, stillUnread: Int): List<Chat> {
    val still = stillUnread.coerceAtLeast(0)
    return chats.map { chat ->
        if (chat.id == chatId && chat.unreadReactionsCount != still) {
            chat.copy(unreadReactionsCount = still)
        } else {
            chat
        }
    }
}

internal fun applyUnreadMentionsDelta(chats: List<Chat>, chatId: PeerId, delta: Int): List<Chat> {
    if (delta == 0) return chats
    return chats.map { chat ->
        if (chat.id == chatId) {
            chat.copy(unreadMentionsCount = (chat.unreadMentionsCount + delta).coerceAtLeast(0))
        } else {
            chat
        }
    }
}

internal fun applyUnreadReactionsDelta(chats: List<Chat>, chatId: PeerId, delta: Int): List<Chat> {
    if (delta == 0) return chats
    return chats.map { chat ->
        if (chat.id == chatId) {
            chat.copy(unreadReactionsCount = (chat.unreadReactionsCount + delta).coerceAtLeast(0))
        } else {
            chat
        }
    }
}

internal fun applyIncomingMessage(chats: List<Chat>, message: Message): List<Chat> {
    val existing = chats.firstOrNull { it.id == message.id.chatId } ?: return chats
    val unread =
        if (message.outgoing || message.id.id <= existing.readInboxMaxId ||
            message.id.id == existing.lastMessageId
        ) {
            existing.unreadCount
        } else {
            existing.unreadCount + 1
        }
    val updated = existing.copy(
        title = existing.title,
        lastMessagePreview = message.chatListPreviewSource(),
        lastMessageOutgoing = message.outgoing,
        lastMessageSenderName = message.senderName,
        lastMessageMediaKind = message.mediaKind,
        lastMessageDate = message.date,
        unreadCount = unread,
        lastMessageId = message.id.id,
        lastMediaThumbCacheKey = message.thumbCacheKey ?: existing.lastMediaThumbCacheKey,
    )
    return mergeChats(chats, listOf(updated))
}
