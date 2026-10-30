package org.monogram.core.models

data class MessageSendOptions(
    val silent: Boolean = false,
    val scheduleDate: Int? = null,
    val noWebpage: Boolean = false,
)

sealed interface OutgoingMessageResult {
    data class Sent(val message: Message) : OutgoingMessageResult
    data class Scheduled(val message: ScheduledMessage) : OutgoingMessageResult
}
