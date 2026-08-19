package org.monogram.domain.repository

import org.monogram.domain.models.DialogPeerType

/** Sends a plain text message through the selected account's owned MTProto session. */
interface MtProtoTextMessageRepository {
    suspend fun sendText(
        chatId: Long,
        peerType: DialogPeerType,
        text: String,
    )

    suspend fun editText(
        chatId: Long,
        peerType: DialogPeerType,
        messageId: Long,
        text: String,
    )

    suspend fun setEmojiReaction(
        chatId: Long,
        peerType: DialogPeerType,
        messageId: Long,
        emoji: String?,
    )

    suspend fun setPinned(
        chatId: Long,
        peerType: DialogPeerType,
        messageId: Long,
        pinned: Boolean,
    )

    suspend fun forwardToSelf(
        chatId: Long,
        peerType: DialogPeerType,
        messageId: Long,
    )

    suspend fun sendScheduledNow(
        chatId: Long,
        peerType: DialogPeerType,
        messageId: Long,
    )

    suspend fun clearHistory(
        chatId: Long,
        peerType: DialogPeerType,
        revoke: Boolean,
    )
}
