package org.monogram.presentation.features.chats.conversation.logic

import org.monogram.domain.models.MessageModel
import org.monogram.presentation.features.chats.conversation.ChatComponent

internal enum class UnreadShortcutType {
    Mention,
    Reaction
}

internal sealed interface UnreadShortcutAction {
    data class ScrollToMessage(val messageId: Long) : UnreadShortcutAction
    data object LoadOlder : UnreadShortcutAction
    data object LoadNewer : UnreadShortcutAction
    data object None : UnreadShortcutAction
}

internal fun resolveUnreadShortcutAction(
    messages: List<MessageModel>,
    targetChatId: Long,
    isLatestLoaded: Boolean,
    isOldestLoaded: Boolean,
    type: UnreadShortcutType
): UnreadShortcutAction {
    val targetMessageId = messages
        .asSequence()
        .filter { message ->
            message.chatId == targetChatId && when (type) {
                UnreadShortcutType.Mention -> message.hasUnreadMention
                UnreadShortcutType.Reaction -> message.hasUnreadReactions
            }
        }
        .maxOfOrNull(MessageModel::id)

    return when {
        targetMessageId != null -> UnreadShortcutAction.ScrollToMessage(targetMessageId)
        !isLatestLoaded -> UnreadShortcutAction.LoadNewer
        !isOldestLoaded -> UnreadShortcutAction.LoadOlder
        else -> UnreadShortcutAction.None
    }
}

internal fun ChatComponent.State.clearUnreadShortcut(
    targetChatId: Long,
    type: UnreadShortcutType
): ChatComponent.State {
    fun MessageModel.clearedIfNeeded(): MessageModel {
        if (chatId != targetChatId) return this
        return when (type) {
            UnreadShortcutType.Mention ->
                if (hasUnreadMention) copy(hasUnreadMention = false) else this

            UnreadShortcutType.Reaction ->
                if (hasUnreadReactions) copy(hasUnreadReactions = false) else this
        }
    }

    return copy(
        unreadMentionCount = if (type == UnreadShortcutType.Mention) 0 else unreadMentionCount,
        unreadReactionCount = if (type == UnreadShortcutType.Reaction) 0 else unreadReactionCount,
        messages = messages.map(MessageModel::clearedIfNeeded),
        rootMessage = rootMessage?.clearedIfNeeded()
    )
}
