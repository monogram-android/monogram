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
    val sameSender = when {
        current.senderId > 0L && neighbor.senderId > 0L -> current.senderId == neighbor.senderId
        current.isOutgoing && neighbor.isOutgoing -> true
        current.senderName.isNotBlank() && current.senderName == neighbor.senderName -> true
        else -> false
    }
    if (!sameSender) return false
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
