package org.monogram.domain.models

/**
 * Stable signed chat identifier for a Telegram peer.
 *
 * This representation is protocol-compatible and deliberately independent of a client backend.
 */
object TelegramPeerChatId {
    private const val CHANNEL_OFFSET = 1_000_000_000_000L

    fun encode(peerType: DialogPeerType, peerId: Long): Long {
        require(peerId > 0L) { "Telegram peer id must be positive" }
        return when (peerType) {
            DialogPeerType.PRIVATE -> peerId
            DialogPeerType.BASIC_GROUP -> -peerId
            DialogPeerType.SUPERGROUP,
            DialogPeerType.CHANNEL -> -(CHANNEL_OFFSET + peerId)
            DialogPeerType.UNKNOWN -> error("Cannot encode an unknown Telegram peer")
        }
    }

    fun decode(chatId: Long, isChannel: Boolean? = null): Peer {
        require(chatId != 0L) { "Telegram chat id must not be zero" }
        return when {
            chatId > 0L -> Peer(DialogPeerType.PRIVATE, chatId)
            chatId <= -CHANNEL_OFFSET - 1L -> {
                requireNotNull(isChannel) {
                    "A channel-range chat id requires its projected channel kind"
                }
                Peer(
                    type = if (isChannel) DialogPeerType.CHANNEL else DialogPeerType.SUPERGROUP,
                    id = -(chatId + CHANNEL_OFFSET),
                )
            }
            else -> Peer(DialogPeerType.BASIC_GROUP, -chatId)
        }
    }

    data class Peer(
        val type: DialogPeerType,
        val id: Long,
    )
}
