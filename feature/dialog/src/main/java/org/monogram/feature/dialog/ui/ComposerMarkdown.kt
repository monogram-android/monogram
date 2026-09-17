package org.monogram.feature.dialog.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.monogram.core.models.parseMarkdownMapped
import org.monogram.core.models.wrapMarkdown as wrapMarkdownCore
import org.monogram.core.models.wrapQuote as wrapQuoteCore

internal fun wrapMarkdown(
    value: TextFieldValue,
    left: String,
    right: String = left,
): TextFieldValue {
    val lo = value.selection.min.coerceIn(0, value.text.length)
    val hi = value.selection.max.coerceIn(lo, value.text.length)
    val selectedLen = hi - lo
    val edit = wrapMarkdownCore(value.text, lo, hi, left, right)
    val innerStart = lo + left.length
    val selection = if (selectedLen > 0) {
        TextRange(innerStart, innerStart + selectedLen)
    } else {
        TextRange(edit.cursor)
    }
    return TextFieldValue(text = edit.text, selection = selection)
}

internal fun wrapQuote(value: TextFieldValue): TextFieldValue {
    val edit = wrapQuoteCore(value.text, value.selection.min, value.selection.max)
    return TextFieldValue(text = edit.text, selection = TextRange(edit.cursor))
}

internal fun selectedComposerPlain(value: TextFieldValue): String {
    val lo = value.selection.min.coerceIn(0, value.text.length)
    val hi = value.selection.max.coerceIn(lo, value.text.length)
    if (lo >= hi) return ""
    val mapped = parseMarkdownMapped(value.text)
    val a = mapped.origToDisp.getOrElse(lo) { 0 }
    val b = mapped.origToDisp.getOrElse(hi) { mapped.styled.text.length }
    val start = minOf(a, b).coerceIn(0, mapped.styled.text.length)
    val end = maxOf(a, b).coerceIn(start, mapped.styled.text.length)
    return mapped.styled.text.substring(start, end)
}

internal fun deleteComposerSelection(value: TextFieldValue): TextFieldValue {
    val lo = value.selection.min.coerceIn(0, value.text.length)
    val hi = value.selection.max.coerceIn(lo, value.text.length)
    if (lo >= hi) return value
    return TextFieldValue(text = value.text.removeRange(lo, hi), selection = TextRange(lo))
}

internal fun insertComposerText(value: TextFieldValue, text: String): TextFieldValue {
    val lo = value.selection.min.coerceIn(0, value.text.length)
    val hi = value.selection.max.coerceIn(lo, value.text.length)
    return TextFieldValue(
        text = value.text.replaceRange(lo, hi, text),
        selection = TextRange(lo + text.length),
    )
}

internal fun selectAllComposer(value: TextFieldValue): TextFieldValue =
    value.copy(selection = TextRange(0, value.text.length))

internal fun collapseComposerSelection(value: TextFieldValue): TextFieldValue {
    if (value.selection.collapsed) return value
    return value.copy(selection = TextRange(value.selection.max), composition = null)
}
