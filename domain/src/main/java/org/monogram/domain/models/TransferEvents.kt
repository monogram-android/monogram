package org.monogram.domain.models

sealed interface FileDownloadEvent {
    val fileId: Int

    data class Progress(
        override val fileId: Int,
        val progress: Float
    ) : FileDownloadEvent

    data class Completed(
        override val fileId: Int,
        val path: String
    ) : FileDownloadEvent
}

sealed interface MessageDownloadEvent {
    val chatId: Long
    val messageId: Long
    val fileId: Int

    data class Progress(
        override val chatId: Long,
        override val messageId: Long,
        override val fileId: Int,
        val progress: Float
    ) : MessageDownloadEvent

    data class Completed(
        override val chatId: Long,
        override val messageId: Long,
        override val fileId: Int,
        val path: String
    ) : MessageDownloadEvent

    data class Cancelled(
        override val chatId: Long,
        override val messageId: Long,
        override val fileId: Int
    ) : MessageDownloadEvent
}

data class MessageUploadProgressEvent(
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    val progress: Float
)

data class MessageDeletedEvent(
    val chatId: Long,
    val messageIds: List<Long>
)

data class MessageIdUpdatedEvent(
    val chatId: Long,
    val oldMessageId: Long,
    val message: MessageModel
)
