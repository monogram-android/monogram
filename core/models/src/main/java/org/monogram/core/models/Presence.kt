package org.monogram.core.models

data class ChatListOutgoingMark(
    val text: String,
    val read: Boolean,
    val pending: Boolean,
    val double: Boolean,
)

object Presence {
    fun outgoingRead(
        outgoing: Boolean,
        pending: Boolean,
        messageId: Int,
        readOutboxMaxId: Int,
    ): Boolean = outgoing && !pending && messageId > 0 && messageId <= readOutboxMaxId

    fun ticks(pending: Boolean, failed: Boolean, read: Boolean): String = when {
        failed -> "!"
        pending -> "\u2026"
        read -> "\u2713\u2713"
        else -> "\u2713"
    }

    fun lastDialogOutgoing(
        flaggedOutgoing: Boolean,
        lastMessageId: Int,
        readInboxMaxId: Int,
        unreadCount: Int,
    ): Boolean {
        if (flaggedOutgoing) return true
        return lastMessageId > 0 && unreadCount <= 0 && lastMessageId > readInboxMaxId
    }

    fun chatListOutgoingMark(
        outgoing: Boolean,
        isChannel: Boolean,
        lastMessageId: Int,
        readInboxMaxId: Int,
        readOutboxMaxId: Int,
        unreadCount: Int,
    ): ChatListOutgoingMark? {
        if (isChannel) return null
        if (!lastDialogOutgoing(outgoing, lastMessageId, readInboxMaxId, unreadCount)) return null
        val pending = lastMessageId <= 0
        val read = outgoingRead(
            outgoing = true,
            pending = pending,
            messageId = lastMessageId,
            readOutboxMaxId = readOutboxMaxId,
        )
        return ChatListOutgoingMark(
            text = ticks(pending = pending, failed = false, read = read),
            read = read,
            pending = pending,
            double = read,
        )
    }

    fun withReadState(messages: List<Message>, readOutboxMaxId: Int): List<Message> =
        messages.map { message ->
            message.copy(
                read = outgoingRead(
                    outgoing = message.outgoing,
                    pending = message.pending,
                    messageId = message.id.id,
                    readOutboxMaxId = readOutboxMaxId,
                ),
            )
        }
}
