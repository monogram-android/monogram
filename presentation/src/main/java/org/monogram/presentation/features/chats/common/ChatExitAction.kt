package org.monogram.presentation.features.chats.common

enum class ChatExitAction {
    None,
    Leave,
    Delete
}

fun resolveChatExitAction(
    isMainChat: Boolean,
    isGroup: Boolean,
    isChannel: Boolean,
    isMember: Boolean,
    canDeleteChat: Boolean
): ChatExitAction {
    if (!isMainChat) return ChatExitAction.None

    return when {
        isGroup || isChannel -> {
            if (isMember) ChatExitAction.Leave else ChatExitAction.None
        }

        canDeleteChat -> ChatExitAction.Delete
        else -> ChatExitAction.None
    }
}
