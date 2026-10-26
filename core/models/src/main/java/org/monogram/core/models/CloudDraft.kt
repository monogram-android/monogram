package org.monogram.core.models

data class CloudDraft(
    val chatId: PeerId,
    val threadId: Int = 0,
    val text: String = "",
    val entitiesJson: String? = null,
    val replyToMessageId: Int = 0,
    val date: Int = 0,
    val noWebpage: Boolean = false,
    val hasUnsupportedContent: Boolean = false,
)
