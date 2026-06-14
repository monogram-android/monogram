package org.monogram.presentation.features.chats.conversation.logic

import org.monogram.domain.models.ChatViewportCacheEntry
import org.monogram.presentation.features.chats.conversation.ChatScrollCommand
import org.monogram.presentation.features.chats.conversation.ScrollAlign

internal sealed interface InitialChatScrollTarget {
    data class AroundMessage(
        val messageId: Long,
        val command: ChatScrollCommand,
        val highlight: Boolean,
        val backfillNewerAfterInitialLoad: Boolean = false
    ) : InitialChatScrollTarget

    data class Bottom(val command: ChatScrollCommand) : InitialChatScrollTarget
    data class Comments(val command: ChatScrollCommand) : InitialChatScrollTarget
}

internal fun resolveInitialChatScrollTarget(
    explicitMessageId: Long?,
    savedViewport: ChatViewportCacheEntry?,
    firstUnreadMessageId: Long?,
    unreadCount: Int = 0,
    backfillUnreadThreshold: Int = 50,
    isComments: Boolean
): InitialChatScrollTarget {
    if (explicitMessageId != null) {
        return InitialChatScrollTarget.AroundMessage(
            messageId = explicitMessageId,
            highlight = true,
            command = ChatScrollCommand.JumpToMessage(
                messageId = explicitMessageId,
                highlight = true,
                align = ScrollAlign.Center,
                animated = false
            )
        )
    }

    val savedAnchorId = savedViewport?.anchorMessageId
    if (savedViewport != null && !savedViewport.atBottom && savedAnchorId != null) {
        return InitialChatScrollTarget.AroundMessage(
            messageId = savedAnchorId,
            highlight = false,
            command = ChatScrollCommand.RestoreViewport(
                anchorMessageId = savedAnchorId,
                anchorOffsetPx = savedViewport.anchorOffsetPx,
                atBottom = false
            )
        )
    }

    if (isComments) {
        return InitialChatScrollTarget.Comments(
            command = savedViewport?.let {
                ChatScrollCommand.RestoreViewport(
                    anchorMessageId = it.anchorMessageId,
                    anchorOffsetPx = it.anchorOffsetPx,
                    atBottom = it.atBottom
                )
            } ?: ChatScrollCommand.ScrollToStart(animated = false)
        )
    }

    if (firstUnreadMessageId != null) {
        return InitialChatScrollTarget.AroundMessage(
            messageId = firstUnreadMessageId,
            highlight = false,
            backfillNewerAfterInitialLoad = !isComments && unreadCount > backfillUnreadThreshold,
            command = ChatScrollCommand.JumpToMessage(
                messageId = firstUnreadMessageId,
                highlight = false,
                align = ScrollAlign.Center,
                animated = false
            )
        )
    }

    if (savedViewport != null && savedViewport.atBottom) {
        return InitialChatScrollTarget.Bottom(
            command = ChatScrollCommand.RestoreViewport(
                anchorMessageId = savedViewport.anchorMessageId,
                anchorOffsetPx = savedViewport.anchorOffsetPx,
                atBottom = true
            )
        )
    }

    return InitialChatScrollTarget.Bottom(
        command = savedViewport?.let {
            ChatScrollCommand.RestoreViewport(
                anchorMessageId = it.anchorMessageId,
                anchorOffsetPx = it.anchorOffsetPx,
                atBottom = it.atBottom
            )
        } ?: ChatScrollCommand.ScrollToBottom(animated = false)
    )
}
