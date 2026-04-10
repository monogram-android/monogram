package org.monogram.presentation.features.chats.currentChat.components

import org.monogram.domain.models.MessageModel

internal fun shouldGroupSenderBlock(
    current: MessageModel,
    neighbor: MessageModel?,
    dateBreak: Boolean
): Boolean {
    if (neighbor == null) return false
    if (current.senderId <= 0L || neighbor.senderId <= 0L) return false
    if (current.senderId != neighbor.senderId) return false
    if (current.senderName != neighbor.senderName) return false
    if (current.senderCustomTitle != neighbor.senderCustomTitle) return false
    return !dateBreak
}
