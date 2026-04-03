package org.monogram.presentation.features.chats.currentChat.components.inputbar

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.math.max

fun findOccurrences(text: String, query: String, ignoreCase: Boolean = true): List<IntRange> {
    if (query.isBlank()) return emptyList()
    val result = mutableListOf<IntRange>()
    var start = 0
    while (start < text.length) {
        val index = text.indexOf(query, startIndex = start, ignoreCase = ignoreCase)
        if (index == -1) break
        val endExclusive = index + query.length
        result += index until endExclusive
        start = max(index + 1, endExclusive)
    }
    return result
}

fun applyReplaceAtRange(
    currentValue: TextFieldValue,
    range: IntRange,
    replacement: String
): TextFieldValue {
    val oldText = currentValue.text
    if (range.first !in oldText.indices) return currentValue
    val endExclusive = range.last + 1
    if (endExclusive > oldText.length || range.first >= endExclusive) return currentValue

    val newText = oldText.replaceRange(range.first, endExclusive, replacement)
    val incoming = TextFieldValue(
        text = newText,
        selection = TextRange(range.first + replacement.length)
    )
    return mergeInputTextValuePreservingAnnotations(currentValue, incoming)
}

fun applyReplaceAll(
    currentValue: TextFieldValue,
    query: String,
    replacement: String
): TextFieldValue {
    if (query.isBlank()) return currentValue
    val newText = currentValue.text.replace(query, replacement, ignoreCase = true)
    val incoming = TextFieldValue(text = newText, selection = TextRange(newText.length))
    return mergeInputTextValuePreservingAnnotations(currentValue, incoming)
}
