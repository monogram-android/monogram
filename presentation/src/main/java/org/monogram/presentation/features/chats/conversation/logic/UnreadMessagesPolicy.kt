package org.monogram.presentation.features.chats.conversation.logic

import org.monogram.domain.models.MessageModel
import org.monogram.presentation.features.chats.conversation.ChatComponent
import org.monogram.presentation.features.chats.conversation.ChatViewportPhase

internal fun ChatComponent.State.withUnreadSessionFromChat(
    chatUnreadCount: Int,
    chatLastReadInboxMessageId: Long
): ChatComponent.State {
    val nextLastReadInboxMessageId = maxOf(lastReadInboxMessageId, chatLastReadInboxMessageId)

    if (chatUnreadCount <= 0 || (isAtBottom && viewportPhase == ChatViewportPhase.Settled)) {
        return copy(lastReadInboxMessageId = nextLastReadInboxMessageId)
            .withUnreadSessionCleared()
    }

    val currentUnreadCount = maxOf(unreadCount, unreadSeparatorCount)
    val hasReadPointerAdvanced = chatLastReadInboxMessageId > lastReadInboxMessageId
    val nextUnreadCount = when {
        currentUnreadCount <= 0 -> chatUnreadCount
        chatUnreadCount > currentUnreadCount -> chatUnreadCount
        hasReadPointerAdvanced -> chatUnreadCount
        chatUnreadCount < currentUnreadCount -> currentUnreadCount
        else -> chatUnreadCount
    }

    return copy(
        unreadCount = nextUnreadCount,
        unreadSeparatorCount = nextUnreadCount,
        lastReadInboxMessageId = nextLastReadInboxMessageId,
        unreadSeparatorLastReadInboxMessageId = nextLastReadInboxMessageId
    )
}

internal fun ChatComponent.State.withIncomingUnreadMessage(
    rootChatId: Long,
    message: MessageModel
): ChatComponent.State {
    if (isAtBottom || rootMessage != null || message.chatId != rootChatId || message.isOutgoing) {
        return this
    }
    if (messages.any { it.id == message.id && it.chatId == message.chatId }) {
        return this
    }

    val nextUnreadCount = maxOf(unreadCount, unreadSeparatorCount) + 1
    return copy(
        unreadCount = nextUnreadCount,
        unreadSeparatorCount = nextUnreadCount,
        unreadSeparatorLastReadInboxMessageId = unreadSeparatorLastReadInboxMessageId
            .takeIf { it > 0L }
            ?: lastReadInboxMessageId
    )
}

internal fun ChatComponent.State.withInboxReadUpdate(
    readChatId: Long,
    readMessageId: Long,
    updateUnreadSession: Boolean
): ChatComponent.State {
    val previousLastReadInboxMessageId = lastReadInboxMessageId
    val nextLastReadInboxMessageId = if (updateUnreadSession) {
        maxOf(previousLastReadInboxMessageId, readMessageId)
    } else {
        previousLastReadInboxMessageId
    }

    var newlyReadCount = 0
    var hasMessageChanges = false
    val updatedMessages = messages.map { message ->
        val shouldMarkRead = message.chatId == readChatId &&
                !message.isOutgoing &&
                !message.isRead &&
                message.id <= readMessageId
        if (!shouldMarkRead) {
            message
        } else {
            hasMessageChanges = true
            if (updateUnreadSession &&
                message.id > previousLastReadInboxMessageId &&
                message.id <= nextLastReadInboxMessageId
            ) {
                newlyReadCount++
            }
            message.copy(isRead = true)
        }
    }

    if (!updateUnreadSession) {
        return if (hasMessageChanges) copy(messages = updatedMessages) else this
    }

    val currentUnreadCount = maxOf(unreadCount, unreadSeparatorCount)
    val nextUnreadCount = (currentUnreadCount - newlyReadCount).coerceAtLeast(0)
    val nextState = copy(
        messages = if (hasMessageChanges) updatedMessages else messages,
        unreadCount = nextUnreadCount,
        unreadSeparatorCount = nextUnreadCount,
        lastReadInboxMessageId = nextLastReadInboxMessageId,
        unreadSeparatorLastReadInboxMessageId = if (nextUnreadCount > 0) {
            nextLastReadInboxMessageId
        } else {
            0L
        }
    )

    return if (nextUnreadCount == 0) nextState.withUnreadSessionCleared() else nextState
}

internal fun ChatComponent.State.withVisibleMessagesRead(
    readChatId: Long,
    visibleMessageIds: Collection<Long>
): ChatComponent.State {
    val lastVisibleIncomingId = messages
        .asSequence()
        .filter { it.chatId == readChatId && !it.isOutgoing && it.id in visibleMessageIds }
        .maxOfOrNull(MessageModel::id)
        ?: return this

    return withInboxReadUpdate(
        readChatId = readChatId,
        readMessageId = lastVisibleIncomingId,
        updateUnreadSession = readChatId == chatId
    )
}

internal fun ChatComponent.State.withUnreadSessionCleared(): ChatComponent.State =
    copy(
        unreadCount = 0,
        unreadSeparatorCount = 0,
        unreadSeparatorLastReadInboxMessageId = 0L
    )
