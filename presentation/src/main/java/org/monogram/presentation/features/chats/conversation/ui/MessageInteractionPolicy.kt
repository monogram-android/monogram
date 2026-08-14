package org.monogram.presentation.features.chats.conversation.ui

internal enum class MessageInteractionAction {
    None,
    OpenOptions,
    ToggleSelection
}

internal data class MessageInteractionResolution(
    val action: MessageInteractionAction,
    val messageId: Long
)

internal fun resolveMessageInteraction(
    messageId: Long,
    isLongPress: Boolean,
    isSelectionMode: Boolean,
    entityTapConsumed: Boolean
): MessageInteractionResolution {
    val action = when {
        entityTapConsumed -> MessageInteractionAction.None
        isLongPress || isSelectionMode -> MessageInteractionAction.ToggleSelection
        else -> MessageInteractionAction.OpenOptions
    }
    return MessageInteractionResolution(action = action, messageId = messageId)
}
