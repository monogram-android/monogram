package org.monogram.feature.dialog.ui

import org.monogram.core.models.Message
import org.monogram.core.models.isForwardSourceShape

internal const val MaxForwardSelection = 100

internal fun isForwardSelectionCandidate(message: Message): Boolean = message.isForwardSourceShape()

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

internal enum class HistoryMessageGesture { Tap, LongPress }

internal data class HistoryGestureEffect(
    val selectedIds: List<Int>,
    val textSelectionId: Int?,
    val showMenu: Boolean,
)

internal fun historyMessageGesture(
    selectedIds: Collection<Int>,
    textSelectionId: Int?,
    row: List<Message>,
    gesture: HistoryMessageGesture,
    forText: Boolean = false,
): HistoryGestureEffect {
    val kept = selectedIds.distinct().sorted()
    val selectable = row.isNotEmpty() && row.all(::isForwardSelectionCandidate)
    val rowSelected = selectable && row.all { it.id.id in kept }
    val textId = if (selectable && row.any { !it.text.isNullOrBlank() }) row.first().id.id else null
    return when (gesture) {
        HistoryMessageGesture.Tap -> when {
            textSelectionId != null && selectable && row.none { it.id.id == textSelectionId } ->
                HistoryGestureEffect(
                    toggleForwardSelection(kept, row),
                    textSelectionId = null,
                    showMenu = false,
                )

            textSelectionId != null ->
                HistoryGestureEffect(kept, textSelectionId, showMenu = false)

            kept.isNotEmpty() && selectable -> HistoryGestureEffect(
                toggleForwardSelection(kept, row),
                textSelectionId = null,
                showMenu = false,
            )

            else -> HistoryGestureEffect(kept, textSelectionId, showMenu = true)
        }

        HistoryMessageGesture.LongPress -> when {
            !selectable -> HistoryGestureEffect(kept, textSelectionId, showMenu = false)
            rowSelected && forText && textId != null -> HistoryGestureEffect(
                kept,
                textId,
                showMenu = false
            )

            rowSelected -> HistoryGestureEffect(kept, null, showMenu = false)
            else -> HistoryGestureEffect(
                toggleForwardSelection(kept, row),
                textSelectionId = null,
                showMenu = false,
            )
        }
    }
}

internal fun initialTextSelectionEnd(text: String): Int {
    if (text.isEmpty()) return 0
    val wordEnd = text.indexOfFirst { it.isWhitespace() }
    return if (wordEnd <= 0) text.length else wordEnd
}

internal fun copiedSelection(text: String, start: Int, end: Int): String {
    val lo = start.coerceIn(0, text.length)
    val hi = end.coerceIn(lo, text.length)
    return text.substring(lo, hi)
}

internal fun backOutMessageSelection(
    selectedIds: List<Int>,
    textSelectionId: Int?,
    menuOpen: Boolean,
): HistoryGestureEffect = when {
    textSelectionId != null -> HistoryGestureEffect(selectedIds, null, showMenu = false)
    menuOpen -> HistoryGestureEffect(selectedIds, null, showMenu = false)
    else -> HistoryGestureEffect(emptyList(), null, showMenu = false)
}
