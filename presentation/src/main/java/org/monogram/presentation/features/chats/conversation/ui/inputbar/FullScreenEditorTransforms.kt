package org.monogram.presentation.features.chats.conversation.ui.inputbar

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import org.monogram.domain.models.MessageEntityType

internal fun currentTextUrl(value: TextFieldValue): String? {
    val range = normalizedSelection(value.selection) ?: return null
    return value.annotatedString.getStringAnnotations(RICH_ENTITY_TAG, range.start, range.end)
        .firstOrNull { decodeRichEntity(it.item) is MessageEntityType.TextUrl }
        ?.let { decodeRichEntity(it.item) as? MessageEntityType.TextUrl }
        ?.url
}

internal fun currentPreLanguage(value: TextFieldValue): String {
    val range = normalizedSelection(value.selection) ?: return ""
    return value.annotatedString.getStringAnnotations(RICH_ENTITY_TAG, range.start, range.end)
        .firstOrNull { decodeRichEntity(it.item) is MessageEntityType.Pre }
        ?.let { decodeRichEntity(it.item) as? MessageEntityType.Pre }
        ?.language
        .orEmpty()
}

internal fun selectedTextOrNull(value: TextFieldValue): String? {
    val selection = normalizedSelection(value.selection) ?: return null
    return value.text.substring(selection.start, selection.end)
}

internal fun replaceSelection(value: TextFieldValue, replacement: String): TextFieldValue {
    val rawSelection = if (value.selection.start <= value.selection.end) {
        value.selection
    } else {
        TextRange(value.selection.end, value.selection.start)
    }
    val maxLength = value.annotatedString.length
    val selection = TextRange(
        start = rawSelection.start.coerceIn(0, maxLength),
        end = rawSelection.end.coerceIn(0, maxLength)
    )
    val newAnnotated = buildAnnotatedString {
        append(value.annotatedString.subSequence(0, selection.start))
        append(replacement)
        append(value.annotatedString.subSequence(selection.end, value.annotatedString.length))
    }
    val cursor = selection.start + replacement.length
    return value.copy(annotatedString = newAnnotated, selection = TextRange(cursor, cursor))
}

internal fun insertSnippetAtSelection(value: TextFieldValue, snippet: String): TextFieldValue {
    if (snippet.isBlank()) return value
    val rawSelection = if (value.selection.start <= value.selection.end) {
        value.selection
    } else {
        TextRange(value.selection.end, value.selection.start)
    }
    val maxLength = value.annotatedString.length
    val selection = TextRange(
        start = rawSelection.start.coerceIn(0, maxLength),
        end = rawSelection.end.coerceIn(0, maxLength)
    )
    val newAnnotated = buildAnnotatedString {
        append(value.annotatedString.subSequence(0, selection.start))
        append(snippet)
        append(value.annotatedString.subSequence(selection.end, value.annotatedString.length))
    }
    val cursor = selection.start + snippet.length
    return value.copy(annotatedString = newAnnotated, selection = TextRange(cursor, cursor))
}

internal fun normalizedSelection(selection: TextRange): TextRange? {
    if (selection.start == selection.end) return null
    return if (selection.start <= selection.end) selection else TextRange(
        selection.end,
        selection.start
    )
}

internal fun normalizeEditorUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return if (trimmed.contains("://")) trimmed else "https://$trimmed"
}

internal fun insertMentionAtSelection(value: TextFieldValue): TextFieldValue {
    val rawSelection =
        if (value.selection.start <= value.selection.end) value.selection else TextRange(
            value.selection.end,
            value.selection.start
        )
    val maxLength = value.annotatedString.length
    val selection = TextRange(
        start = rawSelection.start.coerceIn(0, maxLength),
        end = rawSelection.end.coerceIn(0, maxLength)
    )
    val insertion =
        if (selection.start == selection.end) "@" else "@${
            value.text.substring(
                selection.start,
                selection.end
            )
        }"
    val newAnnotated = buildAnnotatedString {
        append(value.annotatedString.subSequence(0, selection.start))
        append(insertion)
        append(value.annotatedString.subSequence(selection.end, value.annotatedString.length))
    }
    val newCursor = selection.start + insertion.length
    return value.copy(annotatedString = newAnnotated, selection = TextRange(newCursor, newCursor))
}

internal fun wrapSelectionWith(
    value: TextFieldValue,
    prefix: String,
    suffix: String,
    placeholder: String = "text"
): TextFieldValue {
    val rawSelection =
        if (value.selection.start <= value.selection.end) value.selection else TextRange(
            value.selection.end,
            value.selection.start
        )
    val maxLength = value.annotatedString.length
    val selection = TextRange(
        start = rawSelection.start.coerceIn(0, maxLength),
        end = rawSelection.end.coerceIn(0, maxLength)
    )
    val selected = value.text.substring(selection.start, selection.end)
    val content = selected.ifEmpty { placeholder }
    val replacement = prefix + content + suffix
    val newAnnotated = buildAnnotatedString {
        append(value.annotatedString.subSequence(0, selection.start))
        append(replacement)
        append(value.annotatedString.subSequence(selection.end, value.annotatedString.length))
    }
    val contentStart = selection.start + prefix.length
    val contentEnd = contentStart + content.length
    return value.copy(
        annotatedString = newAnnotated,
        selection = TextRange(contentStart, contentEnd)
    )
}

internal fun prefixSelectionLines(value: TextFieldValue, prefix: String): TextFieldValue {
    val rawSelection =
        if (value.selection.start <= value.selection.end) value.selection else TextRange(
            value.selection.end,
            value.selection.start
        )
    val maxLength = value.annotatedString.length
    val selection = TextRange(
        start = rawSelection.start.coerceIn(0, maxLength),
        end = rawSelection.end.coerceIn(0, maxLength)
    )
    val selected = value.text.substring(selection.start, selection.end).ifEmpty { "quote" }
    val replacement = selected
        .split('\n')
        .joinToString("\n") { line -> if (line.startsWith(prefix)) line else prefix + line }
    val newAnnotated = buildAnnotatedString {
        append(value.annotatedString.subSequence(0, selection.start))
        append(replacement)
        append(value.annotatedString.subSequence(selection.end, value.annotatedString.length))
    }
    return value.copy(
        annotatedString = newAnnotated,
        selection = TextRange(selection.start, selection.start + replacement.length)
    )
}

internal fun applyMarkdownLink(value: TextFieldValue, url: String): TextFieldValue {
    val label = selectedTextOrNull(value).orEmpty().ifBlank { "link" }
    return replaceSelection(value, "[$label]($url)")
}

internal fun applyHtmlLink(value: TextFieldValue, url: String): TextFieldValue {
    val label = selectedTextOrNull(value).orEmpty().ifBlank { "link" }
    return replaceSelection(value, "<a href=\"$url\">$label</a>")
}

internal fun applyMarkdownPre(value: TextFieldValue, language: String): TextFieldValue {
    val selected = selectedTextOrNull(value).orEmpty().ifBlank { "code" }
    val info = language.trim()
    val prefix = if (info.isBlank()) "```\n" else "```$info\n"
    return replaceSelection(value, prefix + selected + "\n```")
}

internal fun applyHtmlPre(value: TextFieldValue, language: String): TextFieldValue {
    val selected = selectedTextOrNull(value).orEmpty().ifBlank { "code" }
    val info = language.trim()
    val openTag = if (info.isBlank()) "<pre>" else "<pre language=\"$info\">"
    return replaceSelection(value, openTag + selected + "</pre>")
}

internal fun applyMarkupHeading(
    value: TextFieldValue,
    mode: EditorParseMode,
    level: Int
): TextFieldValue {
    val safeLevel = level.coerceIn(1, 6)
    return when (mode) {
        EditorParseMode.Markdown -> prefixSelectionLines(value, "#".repeat(safeLevel) + " ")
        EditorParseMode.Html -> wrapSelectionWith(
            value,
            "<h$safeLevel>",
            "</h$safeLevel>",
            "Heading $safeLevel"
        )

        EditorParseMode.Plain -> value
    }
}

internal fun applyMarkupBulletList(value: TextFieldValue, mode: EditorParseMode): TextFieldValue {
    return when (mode) {
        EditorParseMode.Markdown -> prefixSelectionLines(value, "- ")
        EditorParseMode.Html -> applyHtmlList(value, ordered = false)
        EditorParseMode.Plain -> value
    }
}

internal fun applyMarkupNumberedList(value: TextFieldValue, mode: EditorParseMode): TextFieldValue {
    return when (mode) {
        EditorParseMode.Markdown -> applyMarkdownOrderedList(value)
        EditorParseMode.Html -> applyHtmlList(value, ordered = true)
        EditorParseMode.Plain -> value
    }
}

internal fun applyMarkupDivider(value: TextFieldValue, mode: EditorParseMode): TextFieldValue {
    return when (mode) {
        EditorParseMode.Markdown -> replaceSelection(value, "\n---\n")
        EditorParseMode.Html -> replaceSelection(value, "\n<hr>\n")
        EditorParseMode.Plain -> value
    }
}

internal fun applyMarkupTable(value: TextFieldValue, mode: EditorParseMode): TextFieldValue {
    return when (mode) {
        EditorParseMode.Markdown -> replaceSelection(
            value,
            "| Column 1 | Column 2 |\n| --- | --- |\n| Value 1 | Value 2 |"
        )

        EditorParseMode.Html -> replaceSelection(
            value,
            "<table>\n<tr><th>Column 1</th><th>Column 2</th></tr>\n<tr><td>Value 1</td><td>Value 2</td></tr>\n</table>"
        )

        EditorParseMode.Plain -> value
    }
}

internal fun applyMarkdownOrderedList(value: TextFieldValue): TextFieldValue {
    val rawSelection =
        if (value.selection.start <= value.selection.end) value.selection else TextRange(
            value.selection.end,
            value.selection.start
        )
    val maxLength = value.annotatedString.length
    val selection = TextRange(
        start = rawSelection.start.coerceIn(0, maxLength),
        end = rawSelection.end.coerceIn(0, maxLength)
    )
    val selected = value.text.substring(selection.start, selection.end).ifEmpty { "item" }
    val replacement = selected
        .split('\n')
        .mapIndexed { index, line -> "${index + 1}. $line" }
        .joinToString("\n")
    val newAnnotated = buildAnnotatedString {
        append(value.annotatedString.subSequence(0, selection.start))
        append(replacement)
        append(value.annotatedString.subSequence(selection.end, value.annotatedString.length))
    }
    return value.copy(
        annotatedString = newAnnotated,
        selection = TextRange(selection.start, selection.start + replacement.length)
    )
}

internal fun applyHtmlList(value: TextFieldValue, ordered: Boolean): TextFieldValue {
    val tag = if (ordered) "ol" else "ul"
    val selected = selectedTextOrNull(value).orEmpty().ifBlank { "item" }
    val items = selected
        .split('\n')
        .joinToString("\n") { line -> "<li>${line.ifBlank { "item" }}</li>" }
    return replaceSelection(value, "<$tag>\n$items\n</$tag>")
}
