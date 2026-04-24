package org.monogram.presentation.features.chats.conversation.ui.content

import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel

internal fun MessageModel.extractTextContent(): String? {
    return when (val currentContent = content) {
        is MessageContent.Text -> currentContent.text
        is MessageContent.Photo -> currentContent.caption
        is MessageContent.Video -> currentContent.caption
        is MessageContent.Gif -> currentContent.caption
        is MessageContent.Document -> currentContent.caption
        is MessageContent.Audio -> currentContent.caption
        else -> null
    }
}

internal fun MessageModel.withUpdatedTextContent(newText: String): MessageModel {
    val updatedContent = when (val currentContent = content) {
        is MessageContent.Text -> currentContent.copy(
            text = newText,
            entities = emptyList(),
            webPage = null
        )

        is MessageContent.Photo -> currentContent.copy(caption = newText, entities = emptyList())
        is MessageContent.Video -> currentContent.copy(caption = newText, entities = emptyList())
        is MessageContent.Gif -> currentContent.copy(caption = newText, entities = emptyList())
        is MessageContent.Document -> currentContent.copy(caption = newText, entities = emptyList())
        is MessageContent.Audio -> currentContent.copy(caption = newText, entities = emptyList())
        else -> return this
    }

    return copy(content = updatedContent)
}

