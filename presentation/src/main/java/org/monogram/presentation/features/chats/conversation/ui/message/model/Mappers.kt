package org.monogram.presentation.features.chats.conversation.ui.message.model

import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageEntityType

/**
 * Gets text part for current [MessageEntity]
 **/
internal infix fun String.blockFor(entity: MessageEntity): String =
    safeSubstring(entity.offset, entity.offset.toLong() + entity.length.toLong())

internal fun List<MessageEntity>.inlineEntitiesForBlock(blockEntity: MessageEntity): List<MessageEntity> {
    return entitiesForBlock(blockEntity).filterNot { it.type.isBlockElement() }
}

internal fun List<MessageEntity>.topLevelBlockEntities(): List<MessageEntity> {
    val blockEntities = asSequence()
        .filter { it.type.isBlockElement() }
        .sortedWith(compareBy<MessageEntity> { it.offset }.thenByDescending { it.length })
        .toList()

    return blockEntities.filter { entity ->
        val entityStart = entity.offset
        val entityEnd = (entity.offset.toLong() + entity.length.toLong())
            .coerceAtLeast(entityStart.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

        blockEntities.none { parent ->
            if (parent == entity) return@none false

            val parentStart = parent.offset
            val parentEnd = (parent.offset.toLong() + parent.length.toLong())
                .coerceAtLeast(parentStart.toLong())
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()

            val strictlyContains = parentStart <= entityStart &&
                    parentEnd >= entityEnd &&
                    (parentStart < entityStart || parentEnd > entityEnd)
            val sameRangeQuoteContainer = parentStart == entityStart &&
                    parentEnd == entityEnd &&
                    parent.type.isQuoteBlockElement() &&
                    !entity.type.isQuoteBlockElement()

            strictlyContains || sameRangeQuoteContainer
        }
    }
}

internal fun List<MessageEntity>.entitiesForBlock(blockEntity: MessageEntity): List<MessageEntity> {
    val blockStart = blockEntity.offset
    val blockEnd = (blockEntity.offset.toLong() + blockEntity.length.toLong())
        .coerceAtLeast(blockStart.toLong())
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

    return asSequence()
        .filterNot { it == blockEntity }
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

private fun MessageEntityType.isQuoteBlockElement(): Boolean {
    return when (this) {
        is MessageEntityType.BlockQuote,
        is MessageEntityType.BlockQuoteExpandable -> true

        else -> false
    }
}
