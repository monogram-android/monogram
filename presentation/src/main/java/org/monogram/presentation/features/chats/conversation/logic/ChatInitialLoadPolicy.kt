package org.monogram.presentation.features.chats.conversation.logic

import org.monogram.domain.models.ChatViewportCacheEntry
import org.monogram.presentation.features.chats.conversation.ChatInitialLoadKey

internal fun buildChatInitialLoadKey(
    chatId: Long,
    effectiveThreadId: Long?,
    initialMessageId: Long?,
    savedViewport: ChatViewportCacheEntry?,
    firstUnreadMessageId: Long?,
    rootMessageId: Long?
): ChatInitialLoadKey {
    return ChatInitialLoadKey(
        chatId = chatId,
        effectiveThreadId = effectiveThreadId,
        initialMessageId = initialMessageId,
        savedViewportAnchorMessageId = savedViewport?.anchorMessageId,
        firstUnreadMessageId = firstUnreadMessageId,
        rootMessageId = rootMessageId
    )
}

internal fun shouldStartInitialLoad(
    currentKey: ChatInitialLoadKey?,
    nextKey: ChatInitialLoadKey,
    hasStartedForCurrentContext: Boolean
): Boolean {
    if (!hasStartedForCurrentContext) return true
    return currentKey != nextKey
}
