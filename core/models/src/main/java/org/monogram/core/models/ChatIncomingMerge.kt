package org.monogram.core.models

import java.util.concurrent.ConcurrentHashMap

/**
 * Account generation plus the accepted batch order. Generation 0 is an untracked
 * live edit and always applies. A repeated or older order does not.
 */
object IncomingOrder {
    private val applied = ConcurrentHashMap<Long, Pair<Long, Long>>()

    fun accept(chatId: Long, accountGeneration: Long, acceptedOrder: Long): Boolean {
        if (accountGeneration == 0L) return true
        val previous = applied[chatId]
        if (previous != null) {
            val (generation, order) = previous
            if (accountGeneration < generation) return false
            if (accountGeneration == generation && acceptedOrder < order) return false
        }
        applied[chatId] = accountGeneration to acceptedOrder
        return true
    }
}

/**
 * The one rule for folding an incoming message into a dialog row. The cache, the
 * chat list store and journal replay all use it, so arrival order and repeated
 * delivery give the same row everywhere.
 *
 * A duplicate or already-read message leaves the unread count alone, and a
 * message older than the current top one never replaces the preview.
 */
fun Chat.mergeIncomingMessage(
    message: Message,
    alreadyStored: Boolean = false,
    accountGeneration: Long = 0,
    acceptedOrder: Long = 0,
): Chat {
    if (!IncomingOrder.accept(
            message.id.chatId.value,
            accountGeneration,
            acceptedOrder
        )
    ) return this
    val unread =
        if (alreadyStored || message.outgoing || message.id.id <= readInboxMaxId ||
            message.id.id == lastMessageId
        ) {
            unreadCount
        } else {
            unreadCount + 1
        }
    if (message.id.id < lastMessageId) return copy(unreadCount = unread)
    return copy(
        lastMessagePreview = message.chatListPreviewSource(),
        lastMessageOutgoing = message.outgoing,
        lastMessageSenderName = message.senderName,
        lastMessageMediaKind = message.mediaKind,
        lastMessageDate = message.date,
        unreadCount = unread,
        lastMessageId = message.id.id,
        lastMediaThumbCacheKey = message.thumbCacheKey ?: lastMediaThumbCacheKey,
    )
}
