package org.monogram.core.models

/** Scheduled message IDs use a separate namespace from normal history. */
data class ScheduledMessage(val message: Message, val hasUnsupportedContent: Boolean = true) {
    val canPreviewMedia: Boolean
        get() = message.mediaCacheKey != null && message.mediaKind in setOf(
            "photo", "video", "gif", "video_note"
        )

    val canEditText: Boolean
        get() = !hasUnsupportedContent && message.mediaKind in setOf(
            null, "photo", "video", "gif", "document", "audio"
        )

    val mediaMessageId: MessageId
        get() {
            require(message.id.id > 0)
            return MessageId(message.id.chatId, -1 - message.id.id)
        }
}