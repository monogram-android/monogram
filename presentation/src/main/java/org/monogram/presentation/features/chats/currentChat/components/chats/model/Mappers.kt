package org.monogram.presentation.features.chats.currentChat.components.chats.model

import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageEntityType

/**
 * Gets text part for current [MessageEntity]
 **/
internal infix fun String.blockFor(entity: MessageEntity): String =
    this.substring(entity.offset, entity.offset + entity.length)

/**
 * Checks if [MessageEntityType] is block element
 **/
internal fun MessageEntityType.isBlockElement(): Boolean {
    return when (this) {
        is MessageEntityType.Pre,
        is MessageEntityType.BlockQuote,
        is MessageEntityType.BlockQuoteExpandable -> true
        else -> false
    }
}