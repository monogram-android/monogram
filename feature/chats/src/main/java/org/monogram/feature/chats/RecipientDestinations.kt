package org.monogram.feature.chats

import org.monogram.core.models.Chat

internal fun recipientPaneIds(
    paneIds: List<Long>,
    chat: (Long) -> Chat?,
    selectingRecipient: Boolean,
    canSelectRecipient: (Chat) -> Boolean,
): List<Long> {
    if (!selectingRecipient) return paneIds
    return paneIds.filter { id ->
        val row = chat(id) ?: return@filter false
        canSelectRecipient(row)
    }
}
