package org.monogram.core.common.push

/**
 * One entry of a collapsed chat notification. Android keeps a short message history inside the
 * single per-chat notification so a burst of messages is shown as one conversation instead of
 * replacing the previous message.
 */
data class NotificationMessage(
    val messageId: Int,
    val text: String,
    val timestamp: Long,
    val outgoing: Boolean = false,
)

/**
 * Batching rules for chat notifications. Kept free of Android types so the collapse behaviour is
 * unit tested without a device.
 */
object NotificationBatch {
    /** Upper bound of the history kept in one chat notification. */
    const val MAX_MESSAGES: Int = 8

    /**
     * Appends [incoming] to [existing] and keeps the newest [max] entries. An entry is a repeat when
     * it carries the same Telegram message id, or, for payloads without an id, the same text and
     * timestamp; repeats replace the earlier copy instead of stacking a second bubble.
     */
    fun append(
        existing: List<NotificationMessage>,
        incoming: NotificationMessage,
        max: Int = MAX_MESSAGES,
    ): List<NotificationMessage> {
        if (max <= 0) return emptyList()
        val kept = existing.filterNot { it.isRepeatOf(incoming) }
        return (kept + incoming).takeLast(max)
    }

    /**
     * Message count for a notification that hides message bodies: every push adds one, a quiet
     * repaint keeps the count that is already on screen.
     */
    fun countHidden(previousCount: Int, isNewPush: Boolean): Int =
        (previousCount.coerceAtLeast(0) + if (isNewPush) 1 else 0).coerceAtLeast(1)
}

private fun NotificationMessage.isRepeatOf(other: NotificationMessage): Boolean =
    if (messageId > 0 && other.messageId > 0) {
        messageId == other.messageId
    } else {
        messageId == other.messageId && text == other.text && timestamp == other.timestamp
    }
