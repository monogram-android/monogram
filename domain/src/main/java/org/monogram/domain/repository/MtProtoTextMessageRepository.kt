package org.monogram.domain.repository

import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.MessageEntity

/** Sends a plain text message through the selected account's owned MTProto session. */
interface MtProtoTextMessageRepository {
    suspend fun sendText(
        chatId: Long,
        peerType: DialogPeerType,
        text: String,
        silent: Boolean = false,
        scheduleDate: Int? = null,
        disableLinkPreview: Boolean = false,
    )

    suspend fun sendText(
        chatId: Long,
        peerType: DialogPeerType,
        text: String,
        silent: Boolean,
        scheduleDate: Int?,
        disableLinkPreview: Boolean,
        replyToMessageId: Long?,
        threadId: Long?,
    ) = sendText(chatId, peerType, text, silent, scheduleDate, disableLinkPreview)

    suspend fun sendText(
        chatId: Long,
        peerType: DialogPeerType,
        text: String,
        silent: Boolean,
        scheduleDate: Int?,
        disableLinkPreview: Boolean,
        replyToMessageId: Long?,
        threadId: Long?,
        entities: List<MessageEntity>,
    ) = sendText(
        chatId,
        peerType,
        text,
        silent,
        scheduleDate,
        disableLinkPreview,
        replyToMessageId,
        threadId,
    )

    suspend fun sendTyping(
        chatId: Long,
        peerType: DialogPeerType,
        threadId: Long? = null,
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

    suspend fun forwardMessages(request: ForwardRequest)

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

    suspend fun markMentionsRead(chatId: Long, peerType: DialogPeerType)

    suspend fun markReactionsRead(chatId: Long, peerType: DialogPeerType)
}
