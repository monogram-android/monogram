package org.monogram.domain.models

sealed interface FileDownloadEvent {
    val fileId: Int

    data class Progress(
        override val fileId: Int,
        val progress: Float,
        /** Absolute durable prefix size in bytes, enabling progressive/seek-aware consumers. */
        val downloadedBytes: Long = 0L
    ) : FileDownloadEvent

    data class Completed(
        override val fileId: Int,
        val path: String
    ) : FileDownloadEvent

    data class Cancelled(
        override val fileId: Int
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
        val progress: Float,
        val downloadedBytes: Long = 0L
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

data class MessageSendAcknowledgedEvent(
    val chatId: Long,
    val temporaryMessageId: Long
)
