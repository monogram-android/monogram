package org.monogram.presentation.features.chats.conversation.logic

import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.features.chats.conversation.ChatComponent

internal fun MessageModel.withOptimisticEdit(
    text: String,
    entities: List<MessageEntity>
): MessageModel {
    val updatedContent = when (val currentContent = content) {
        is MessageContent.Text -> currentContent.copy(
            text = text,
            entities = entities
        )

        is MessageContent.Photo -> currentContent.copy(
            caption = text,
            entities = entities
        )

        is MessageContent.Video -> currentContent.copy(
            caption = text,
            entities = entities
        )

        is MessageContent.Document -> currentContent.copy(
            caption = text,
            entities = entities
        )

        is MessageContent.Audio -> currentContent.copy(
            caption = text,
            entities = entities
        )

        is MessageContent.Gif -> currentContent.copy(
            caption = text,
            entities = entities
        )

        is MessageContent.RichMessage -> currentContent.copy(markdownSource = text)
        else -> return this
    }

    return copy(content = updatedContent)
}

internal fun ChatComponent.State.withUpdatedMessage(
    messageId: Long,
    transform: (MessageModel) -> MessageModel
): ChatComponent.State {
    var changed = false

    fun updateMessage(message: MessageModel?): MessageModel? {
        if (message == null || message.id != messageId) return message
        val updated = transform(message)
        if (updated != message) {
            changed = true
        }
        return updated
    }

    val updatedMessages = messages.map { message ->
        if (message.id != messageId) {
            message
        } else {
            val updated = transform(message)
            if (updated != message) {
                changed = true
            }
            updated
        }
    }
    val updatedRootMessage = updateMessage(rootMessage)
    val updatedChecklistMessage = updateMessage(checklistMessage)
    val updatedEditingMessage = updateMessage(editingMessage)

    return if (!changed) {
        this
    } else {
        copy(
            messages = updatedMessages,
            rootMessage = updatedRootMessage,
            checklistMessage = updatedChecklistMessage,
            editingMessage = updatedEditingMessage
        )
    }
}

internal fun ChatComponent.State.withPendingEditedMessage(
    message: MessageModel
): ChatComponent.State {
    val updatedState = withUpdatedMessage(message.id) { current ->
        current.copy(content = message.content)
    }
    return updatedState.copy(
        editingMessage = null,
        pendingEditedMessageIds = updatedState.pendingEditedMessageIds + message.id,
        editRequestTime = 0L
    )
}

internal fun ChatComponent.State.withRevertedPendingEditedMessage(
    originalMessage: MessageModel
): ChatComponent.State {
    val updatedState = withUpdatedMessage(originalMessage.id) { current ->
        current.copy(content = originalMessage.content)
    }
    return updatedState.copy(
        pendingEditedMessageIds = updatedState.pendingEditedMessageIds - originalMessage.id
    )
}

internal fun ChatComponent.State.clearPendingEditedMessage(
    messageId: Long
): ChatComponent.State = copy(
    pendingEditedMessageIds = pendingEditedMessageIds - messageId
)

internal fun ChatComponent.State.preservePendingEditedContent(
    incomingMessage: MessageModel,
    previousMessage: MessageModel?
): MessageModel {
    if (previousMessage == null || incomingMessage.id !in pendingEditedMessageIds) {
        return incomingMessage
    }
    return incomingMessage.copy(content = previousMessage.content)
}
