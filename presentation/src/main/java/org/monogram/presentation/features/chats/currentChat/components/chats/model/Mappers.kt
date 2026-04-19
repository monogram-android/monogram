package org.monogram.presentation.features.chats.currentChat.components.chats.model

import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageEntityType

/**
 * Gets text part for current [MessageEntity]
 **/
internal infix fun String.blockFor(entity: MessageEntity): String =
    safeSubstring(entity.offset, entity.offset.toLong() + entity.length.toLong())

internal fun List<MessageEntity>.inlineEntitiesForBlock(blockEntity: MessageEntity): List<MessageEntity> {
    val blockStart = blockEntity.offset
    val blockEnd = (blockEntity.offset.toLong() + blockEntity.length.toLong())
        .coerceAtLeast(blockStart.toLong())
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

    return asSequence()
        .filterNot { it == blockEntity }
        .filterNot { it.type.isBlockElement() }
        .mapNotNull { entity ->
            val start = entity.offset
            val end = (entity.offset.toLong() + entity.length.toLong())
                .coerceAtLeast(start.toLong())
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()

            if (start < blockStart || end > blockEnd) return@mapNotNull null

            entity.copy(offset = start - blockStart)
        }
        .toList()
}

private fun String.safeSubstring(start: Int, end: Long): String {
    if (isEmpty()) return ""
    val safeStart = start.coerceIn(0, length)
    val safeEnd = end.coerceIn(safeStart.toLong(), length.toLong()).toInt()
    return substring(safeStart, safeEnd)
}

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