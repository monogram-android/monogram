package org.monogram.presentation.features.chats.common

enum class ChatActionScreenContext {
    Chat,
    Profile,
    ListSelection
}

enum class ChatActionType {
    Leave,
    Delete,
    ClearHistory,
    Report,
    Join,
    BlockUser,
    UnblockUser,
    Mute,
    Archive,
    ToggleRead,
    Pin
}

sealed interface ChatActionState {
    data object Idle : ChatActionState
    data class Pending(val action: ChatActionType) : ChatActionState
    data class Success(val action: ChatActionType?) : ChatActionState
    data class Failure(val action: ChatActionType, val message: String) : ChatActionState
}

data class ChatActionPolicy(
    val exitAction: ChatExitAction = ChatExitAction.None,
    val canClearHistory: Boolean = false,
    val canReport: Boolean = false,
    val canJoin: Boolean = false,
    val canBlockOrUnblock: Boolean = false,
    val canMute: Boolean = true,
    val canArchive: Boolean = true,
    val canToggleRead: Boolean = true,
    val canPin: Boolean = false,
    val closeOnExitSuccess: Boolean = false
)

fun resolveChatActionPolicy(
    isMainChat: Boolean,
    isGroup: Boolean,
    isChannel: Boolean,
    isMember: Boolean,
    canDeleteChat: Boolean,
    canReport: Boolean,
    canJoin: Boolean,
    canBlockOrUnblock: Boolean,
    canPin: Boolean,
    context: ChatActionScreenContext
): ChatActionPolicy {
    val exitAction = resolveChatExitAction(
        isMainChat = isMainChat,
        isGroup = isGroup,
        isChannel = isChannel,
        isMember = isMember,
        canDeleteChat = canDeleteChat
    )
    val canClearHistory = isMainChat && ((!isGroup && !isChannel) || isMember)
    return ChatActionPolicy(
        exitAction = exitAction,
        canClearHistory = canClearHistory,
        canReport = isMainChat && canReport,
        canJoin = isMainChat && canJoin,
        canBlockOrUnblock = canBlockOrUnblock,
        canMute = true,
        canArchive = true,
        canToggleRead = true,
        canPin = canPin,
        closeOnExitSuccess = context != ChatActionScreenContext.ListSelection && exitAction != ChatExitAction.None
    )
}
