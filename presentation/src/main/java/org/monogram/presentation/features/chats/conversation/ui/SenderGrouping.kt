package org.monogram.presentation.features.chats.conversation.ui

import org.monogram.domain.models.MessageModel
import org.monogram.presentation.features.chats.conversation.ui.content.GroupedMessageItem
import org.monogram.presentation.features.chats.conversation.ui.content.shouldShowDate

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

internal fun buildSenderGrouping(
    item: GroupedMessageItem,
    olderMsg: MessageModel?,
    newerMsg: MessageModel?
): MessageSenderGrouping {
    val firstMsg = when (item) {
        is GroupedMessageItem.Single -> item.message
        is GroupedMessageItem.Album -> item.messages.first()
    }
    val lastMsg = when (item) {
        is GroupedMessageItem.Single -> item.message
        is GroupedMessageItem.Album -> item.messages.last()
    }

    return MessageSenderGrouping(
        isSameSenderAbove = shouldGroupSenderBlock(
            current = firstMsg,
            neighbor = olderMsg,
            dateBreak = olderMsg?.let { shouldShowDate(firstMsg, it) } ?: true
        ),
        isSameSenderBelow = shouldGroupSenderBlock(
            current = lastMsg,
            neighbor = newerMsg,
            dateBreak = newerMsg?.let { shouldShowDate(it, lastMsg) } ?: true
        )
    )
}
