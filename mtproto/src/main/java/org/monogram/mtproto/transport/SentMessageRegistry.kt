package org.monogram.mtproto.transport

/**
 * Owns copies of sent content-related envelopes until Telegram acknowledges them or the request
 * reaches a terminal outcome. Replays must use the original encrypted bytes and message ID.
 */
internal class SentMessageRegistry(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val messages = LinkedHashMap<Long, ByteArray>()

    init {
        require(capacity > 0) { "Sent message registry capacity must be positive" }
    }

    @Synchronized
    fun track(messageId: Long, packet: ByteArray) {
        require(messageId !in messages) { "MTProto message $messageId is already tracked" }
        check(messages.size < capacity) { "Too many unacknowledged MTProto messages" }
        messages[messageId] = packet.copyOf()
    }

    @Synchronized
    fun copiesFor(messageIds: List<Long>): List<ByteArray> = messageIds.mapNotNull(messages::get).map(ByteArray::copyOf)

    @Synchronized
    fun contains(messageId: Long): Boolean = messageId in messages

    @Synchronized
    fun remove(messageId: Long) {
        messages.remove(messageId)?.fill(0)
    }

    @Synchronized
    fun removeAll(messageIds: List<Long>) {
        messageIds.forEach(::remove)
    }

    @Synchronized
    fun clear() {
        messages.values.forEach { it.fill(0) }
        messages.clear()
    }

    private companion object {
        const val DEFAULT_CAPACITY = 64
    }
}
