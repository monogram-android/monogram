package org.monogram.presentation.features.chats.conversation

import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageSendingState

/**
 * Presentation projection of Telegram's outgoing-message lifecycle. The temporary id is
 * intentionally retained as the key after a success so duplicate terminal updates are no-ops.
 */
object OutgoingMessageReducer {
    data class Key(val chatId: Long, val temporaryMessageId: Long)

    sealed interface State {
        data object PendingLocal : State
        data object Acknowledged : State
        data class Succeeded(val finalMessageId: Long) : State
        data class Failed(val errorCode: Int, val retryable: Boolean) : State
    }

    fun recover(messages: List<MessageModel>): Map<Key, State> = messages
        .asSequence()
        .filter(MessageModel::isOutgoing)
        .mapNotNull { message ->
            val state = when (val sendingState = message.sendingState) {
                MessageSendingState.Pending -> State.PendingLocal
                is MessageSendingState.Failed -> State.Failed(
                    errorCode = sendingState.errorCode,
                    retryable = isRetryable(sendingState.errorCode)
                )

                null -> null
            } ?: return@mapNotNull null
            Key(message.chatId, message.id) to state
        }
        .toMap()

    fun pending(current: Map<Key, State>, message: MessageModel): Map<Key, State> {
        if (!message.isOutgoing || message.sendingState !is MessageSendingState.Pending) return current
        val key = Key(message.chatId, message.id)
        return if (current[key] == State.PendingLocal) current else current + (key to State.PendingLocal)
    }

    fun acknowledged(current: Map<Key, State>, key: Key): Map<Key, State> = when (current[key]) {
        State.PendingLocal -> current + (key to State.Acknowledged)
        null -> current + (key to State.Acknowledged)
        else -> current
    }

    fun succeeded(
        current: Map<Key, State>,
        key: Key,
        finalMessageId: Long
    ): Map<Key, State> = when (current[key]) {
        is State.Succeeded,
        is State.Failed -> current

        else -> current + (key to State.Succeeded(finalMessageId))
    }

    fun failed(
        current: Map<Key, State>,
        key: Key,
        errorCode: Int
    ): Map<Key, State> = when (current[key]) {
        is State.Succeeded,
        is State.Failed -> current

        else -> current + (key to State.Failed(errorCode, isRetryable(errorCode)))
    }

    private fun isRetryable(errorCode: Int): Boolean =
        errorCode == 408 || errorCode == 429 || errorCode >= 500
}
