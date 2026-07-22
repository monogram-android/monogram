package org.monogram.presentation.features.chats.conversation.logic

import org.monogram.domain.models.ChatViewportCacheEntry
import org.monogram.presentation.features.chats.conversation.ChatInitialLoadKey

internal fun buildChatInitialLoadKey(
    chatId: Long,
    effectiveThreadId: Long?,
    explicitMessageId: Long?,
    savedViewport: ChatViewportCacheEntry?,
    firstUnreadMessageId: Long?,
    unreadCount: Int = 0,
    rootMessageId: Long?
): ChatInitialLoadKey {
    val target = resolveInitialChatScrollTarget(
        chatId = chatId,
        explicitMessageId = explicitMessageId,
        savedViewport = savedViewport,
        firstUnreadMessageId = firstUnreadMessageId,
        unreadCount = unreadCount,
        isComments = rootMessageId != null
    )

    return ChatInitialLoadKey(
        chatId = chatId,
        effectiveThreadId = effectiveThreadId,
        rootMessageId = rootMessageId,
        initialTarget = target.perfTargetName(),
        initialAnchorMessageId = target.anchorMessageId,
        backfillNewerAfterInitialLoad =
            (target as? InitialChatScrollTarget.AroundMessage)?.backfillNewerAfterInitialLoad == true
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
