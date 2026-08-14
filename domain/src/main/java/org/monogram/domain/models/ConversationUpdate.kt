package org.monogram.domain.models

sealed interface ConversationUpdate {
    val chatId: Long

    data class Upsert(
        override val chatId: Long,
        val message: MessageModel,
        val isNew: Boolean
    ) : ConversationUpdate

    data class ReplaceTemporaryId(
        override val chatId: Long,
        val temporaryMessageId: Long,
        val message: MessageModel
    ) : ConversationUpdate

    data class Delete(
        override val chatId: Long,
        val messageIds: Set<Long>
    ) : ConversationUpdate

    data class InboxRead(
        override val chatId: Long,
        val lastReadMessageId: Long
    ) : ConversationUpdate

    data class OutboxRead(
        override val chatId: Long,
        val lastReadMessageId: Long
    ) : ConversationUpdate

    data class SendAcknowledged(
        override val chatId: Long,
        val temporaryMessageId: Long
    ) : ConversationUpdate

    data class SendFailed(
        override val chatId: Long,
        val temporaryMessageId: Long,
        val message: MessageModel,
        val errorCode: Int
    ) : ConversationUpdate
}
