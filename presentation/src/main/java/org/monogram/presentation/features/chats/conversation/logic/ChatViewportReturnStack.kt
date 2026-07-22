package org.monogram.presentation.features.chats.conversation.logic

import org.monogram.domain.models.ChatViewportCacheEntry

internal data class ChatViewportReturnPopResult(
    val targetMessageId: Long?,
    val viewport: ChatViewportCacheEntry?
)

internal fun pushViewportReturnTarget(
    viewport: ChatViewportCacheEntry?,
    returnTargetMessageId: Long,
    maxSize: Int
): ChatViewportCacheEntry? {
    viewport ?: return null
    val updatedReturnTargets = viewport.returnToMessageIds
        .filterNot { it == returnTargetMessageId } + returnTargetMessageId
    return viewport.copy(
        returnToMessageIds = updatedReturnTargets.takeLast(maxSize)
    )
}

internal fun popViewportReturnTarget(
    viewport: ChatViewportCacheEntry?
): ChatViewportReturnPopResult {
    viewport ?: return ChatViewportReturnPopResult(targetMessageId = null, viewport = null)
    val targetMessageId = viewport.returnToMessageIds.lastOrNull()
    if (targetMessageId == null) {
        return ChatViewportReturnPopResult(targetMessageId = null, viewport = viewport)
    }

    return ChatViewportReturnPopResult(
        targetMessageId = targetMessageId,
        viewport = viewport.copy(returnToMessageIds = viewport.returnToMessageIds.dropLast(1))
    )
}
