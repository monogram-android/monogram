package org.monogram.feature.dialog.ui

import org.monogram.core.models.Message

internal const val MaxForwardSelection = 100

internal fun isForwardSelectionCandidate(message: Message): Boolean =
    message.id.id > 0 && !message.pending && message.mediaKind != "service"

internal fun toggleForwardSelection(
    selectedIds: Collection<Int>,
    rowMessages: List<Message>,
): List<Int> {
    if (rowMessages.any { !isForwardSelectionCandidate(it) }) {
        return selectedIds.distinct().sorted()
    }
    val rowIds = rowMessages
        .asSequence()
        .filter(::isForwardSelectionCandidate)
        .map { it.id.id }
        .distinct()
        .sorted()
        .toList()
    if (rowIds.isEmpty()) return selectedIds.distinct().sorted()
    val selected = selectedIds.toSet()
    return if (rowIds.all(selected::contains)) {
        (selected - rowIds.toSet()).sorted()
    } else {
        val next = selected + rowIds
        if (next.size > MaxForwardSelection) selected.sorted() else next.sorted()
    }
}

internal fun pruneForwardSelection(
    selectedIds: Collection<Int>,
    messages: List<Message>,
): List<Int> {
    val availableIds = messages.mapTo(HashSet()) { it.id.id }
    return selectedIds.filter(availableIds::contains).distinct().sorted()
}

internal fun selectedForwardableMessages(
    messages: List<Message>,
    selectedIds: Collection<Int>,
    canForward: (Message) -> Boolean,
): List<Message> {
    val selected = selectedIds.toSet()
    return messages
        .groupBy { it.groupedId ?: it.id.id.toLong() }
        .values
        .flatMap { group ->
            if (
                group.all(::isForwardSelectionCandidate) &&
                group.all { message -> message.id.id in selected } &&
                group.all(canForward)
            ) {
                group
            } else {
                emptyList()
            }
        }
        .sortedBy { it.id.id }
}
