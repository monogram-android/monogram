package org.monogram.core.database

import org.monogram.core.database.entity.MessageHoleEntity

internal fun subtractHistoryRange(
    hole: MessageHoleEntity,
    startId: Int,
    endId: Int
): List<MessageHoleEntity> {
    if (startId > endId || endId < hole.startId || startId > hole.endId) return listOf(hole)
    return buildList {
        if (hole.startId < startId) add(hole.copy(endId = startId - 1))
        if (hole.endId > endId) add(hole.copy(startId = endId + 1))
    }
}

internal fun mergeHistoryHoles(holes: List<MessageHoleEntity>): List<MessageHoleEntity> {
    val out = mutableListOf<MessageHoleEntity>()
    for (hole in holes.sortedBy { it.startId }) {
        val last = out.lastOrNull()
        if (last != null && last.chatId == hole.chatId && last.source == hole.source &&
            hole.startId.toLong() <= last.endId.toLong() + 1L
        ) {
            out[out.lastIndex] = last.copy(endId = maxOf(last.endId, hole.endId))
        } else out += hole
    }
    return out
}
